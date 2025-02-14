/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.documentsui;

import static com.android.documentsui.DocumentsActivity.TAG;
import static com.android.documentsui.DocumentsActivity.State.MODE_UNKNOWN;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_DISPLAY_NAME;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_LAST_MODIFIED;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_SIZE;
import static com.android.documentsui.DocumentsActivity.State.SORT_ORDER_UNKNOWN;
import static com.android.documentsui.model.DocumentInfo.getCursorInt;

import android.content.AsyncTaskLoader;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.util.Log;

import com.android.documentsui.DocumentsActivity.State;
import com.android.documentsui.RecentsProvider.StateColumns;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;

import libcore.io.IoUtils;

import java.io.FileNotFoundException;

/// M: Add to support drm
import com.mediatek.drm.OmaDrmStore;

class DirectoryResult implements AutoCloseable {
    ContentProviderClient client;
    Cursor cursor;
    Exception exception;

    int mode = MODE_UNKNOWN;
    int sortOrder = SORT_ORDER_UNKNOWN;

    @Override
    public void close() {
        IoUtils.closeQuietly(cursor);
        ContentProviderClient.releaseQuietly(client);
        cursor = null;
        client = null;
    }
}

public class DirectoryLoader extends AsyncTaskLoader<DirectoryResult> {

    private static final String[] SEARCH_REJECT_MIMES = new String[] { Document.MIME_TYPE_DIR };

    private final ForceLoadContentObserver mObserver = new ForceLoadContentObserver();

    private final int mType;
    private final RootInfo mRoot;
    private DocumentInfo mDoc;
    private final Uri mUri;
    private final int mUserSortOrder;

    private CancellationSignal mSignal;
    private DirectoryResult mResult;

    /// M: add to support drm
    private int mDrmLevel;
    /// M: show previous loader's result @{
    private boolean mIsLoading = false;
    /// @}

    public DirectoryLoader(Context context, int type, RootInfo root, DocumentInfo doc, Uri uri,
            int userSortOrder) {
        super(context, ProviderExecutor.forAuthority(root.authority));
        mType = type;
        mRoot = root;
        mDoc = doc;
        mUri = uri;
        mUserSortOrder = userSortOrder;
        /// M: add to support drm
        mDrmLevel = ((DocumentsActivity) context).getIntent().getIntExtra(OmaDrmStore.DrmExtra.EXTRA_DRM_LEVEL, -1);
    }

    @Override
    public final DirectoryResult loadInBackground() {
        mIsLoading = true;
        synchronized (this) {
            if (isLoadInBackgroundCanceled()) {
                throw new OperationCanceledException();
            }
            mSignal = new CancellationSignal();
        }

        final ContentResolver resolver = getContext().getContentResolver();
        final String authority = mUri.getAuthority();

        final DirectoryResult result = new DirectoryResult();

        int userMode = State.MODE_UNKNOWN;

        // Use default document when searching
        if (mType == DirectoryFragment.TYPE_SEARCH) {
            final Uri docUri = DocumentsContract.buildDocumentUri(
                    mRoot.authority, mRoot.documentId);
            try {
                mDoc = DocumentInfo.fromUri(resolver, docUri);
            } catch (FileNotFoundException e) {
                Log.w(TAG, "Failed to query", e);
                result.exception = e;
                return result;
            }
        }

        // Pick up any custom modes requested by user
        Cursor cursor = null;
        try {
            final Uri stateUri = RecentsProvider.buildState(
                    mRoot.authority, mRoot.rootId, mDoc.documentId);
            cursor = resolver.query(stateUri, null, null, null, null);
            if (cursor.moveToFirst()) {
                userMode = getCursorInt(cursor, StateColumns.MODE);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }

        if (userMode != State.MODE_UNKNOWN) {
            result.mode = userMode;
        } else {
            if ((mDoc.flags & Document.FLAG_DIR_PREFERS_GRID) != 0) {
                result.mode = State.MODE_GRID;
            } else {
                result.mode = State.MODE_LIST;
            }
        }

        if (mUserSortOrder != State.SORT_ORDER_UNKNOWN) {
            result.sortOrder = mUserSortOrder;
        } else {
            if ((mDoc.flags & Document.FLAG_DIR_PREFERS_LAST_MODIFIED) != 0) {
                result.sortOrder = State.SORT_ORDER_LAST_MODIFIED;
            } else {
                result.sortOrder = State.SORT_ORDER_DISPLAY_NAME;
            }
        }

        // Search always uses ranking from provider
        if (mType == DirectoryFragment.TYPE_SEARCH) {
            result.sortOrder = State.SORT_ORDER_UNKNOWN;
        }

        Log.d(TAG, "Loading directory for " + mUri + " with: userMode=" + userMode + ", userSortOrder=" + mUserSortOrder + " --> mode="
                + result.mode + ", sortOrder=" + result.sortOrder + ", drmLevel=" + mDrmLevel);

        ContentProviderClient client = null;
        try {
            client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, authority);

            cursor = client.query(
                    mUri, null, null, null, getQuerySortOrder(result.sortOrder), mSignal);
            cursor.registerContentObserver(mObserver);

            cursor = new RootCursorWrapper(mUri.getAuthority(), mRoot.rootId, cursor, -1);

            if (mType == DirectoryFragment.TYPE_SEARCH) {
                // Filter directories out of search results, for now
                cursor = new FilteringCursorWrapper(cursor, null, SEARCH_REJECT_MIMES);
            } else {
                // Normal directories should have sorting applied
                /// M: add to support drm, only show these drm files match given drm level.
                cursor = new SortingCursorWrapper(cursor, result.sortOrder, mDrmLevel);
            }

            result.client = client;
            result.cursor = cursor;
        } catch (Exception e) {
            /// M: query failed, may happen when do query with provider just been killed(may by LMK),
            /// re query again.
            IoUtils.closeQuietly(cursor);
            ContentProviderClient.releaseQuietly(client);
            Log.w(TAG, "Failed to query " + mUri + ", re query again.", e);
            try {
                client = DocumentsApplication.acquireUnstableProviderOrThrow(resolver, authority);

                cursor = client.query(
                        mUri, null, null, null, getQuerySortOrder(result.sortOrder), mSignal);
                cursor.registerContentObserver(mObserver);

                cursor = new RootCursorWrapper(mUri.getAuthority(), mRoot.rootId, cursor, -1);

                if (mType == DirectoryFragment.TYPE_SEARCH) {
                    // Filter directories out of search results, for now
                    cursor = new FilteringCursorWrapper(cursor, null, SEARCH_REJECT_MIMES);
                } else {
                    // Normal directories should have sorting applied
                    cursor = new SortingCursorWrapper(cursor, result.sortOrder, mDrmLevel);
                }
                result.client = client;
                result.cursor = cursor;
            } catch (Exception e2) {
                Log.w(TAG, "Failed to re query", e2);
                result.exception = e;
                IoUtils.closeQuietly(cursor);
                ContentProviderClient.releaseQuietly(client);
            }
        } finally {
            synchronized (this) {
                mSignal = null;
            }
        }
        mIsLoading = false;
        return result;
    }

    @Override
    public void cancelLoadInBackground() {
        super.cancelLoadInBackground();

        synchronized (this) {
            if (mSignal != null) {
                mSignal.cancel();
            }
        }
    }

    @Override
    public void deliverResult(DirectoryResult result) {
        if (isReset()) {
            IoUtils.closeQuietly(result);
            return;
        }
        DirectoryResult oldResult = mResult;
        mResult = result;

        if (isStarted()) {
            super.deliverResult(result);
        }

        if (oldResult != null && oldResult != result) {
            IoUtils.closeQuietly(oldResult);
        }
    }

    @Override
    protected void onStartLoading() {
        if (mResult != null) {
            deliverResult(mResult);
        }
        /// M: show previous loader's result @{
        boolean contentChanged = takeContentChanged();
        Log.d(TAG, "onStartLoading contentChanged: " + contentChanged + ", mIsLoading: "
                + mIsLoading + ", mResult: " + mResult);
        if (!contentChanged && mIsLoading) {
            return;
        }
        if (contentChanged || mResult == null) {
            forceLoad();
        }
        /// @}
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    public void onCanceled(DirectoryResult result) {
        /// M: show previous loader's result @{
        if (!isReset() && (mResult == null)) {
            deliverResult(result);
            Log.d(TAG, "DirectoryLoader show result when onCanceled");
        } else {
            IoUtils.closeQuietly(result);
        }
        /// @}
    }

    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        IoUtils.closeQuietly(mResult);
        mResult = null;

        getContext().getContentResolver().unregisterContentObserver(mObserver);
    }

    public static String getQuerySortOrder(int sortOrder) {
        switch (sortOrder) {
            case SORT_ORDER_DISPLAY_NAME:
                return Document.COLUMN_DISPLAY_NAME + " ASC";
            case SORT_ORDER_LAST_MODIFIED:
                return Document.COLUMN_LAST_MODIFIED + " DESC";
            case SORT_ORDER_SIZE:
                return Document.COLUMN_SIZE + " DESC";
            default:
                return null;
        }
    }
}
