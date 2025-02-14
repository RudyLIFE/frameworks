/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/// M: multiple user @{
import android.os.storage.IMountService;
import android.os.ServiceManager;
import com.mediatek.common.featureoption.FeatureOption;
import android.os.RemoteException;
import android.os.storage.IMountService;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.ServiceManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.IBinder;
import android.os.UserHandle;
import android.view.WindowManager;
import android.os.Environment;
/// @}

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;

public class KeyguardMultiUserSelectorView extends FrameLayout implements View.OnClickListener {
    private static final String TAG = "KeyguardMultiUserSelectorView";

    private ViewGroup mUsersGrid;
    private KeyguardMultiUserAvatar mActiveUserAvatar;
    private KeyguardHostView.UserSwitcherCallback mCallback;
    private static final int FADE_OUT_ANIMATION_DURATION = 100;

    public KeyguardMultiUserSelectorView(Context context) {
        this(context, null, 0);
    }

    public KeyguardMultiUserSelectorView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardMultiUserSelectorView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    protected void onFinishInflate () {
        int resId = R.id.keyguard_users_grid;
        ///M: support power off alarm
        if(PowerOffAlarmManager.isAlarmBoot()){
            resId = R.id.keyguard_users_grid;
        }
        mUsersGrid = (ViewGroup) findViewById(resId);
        mUsersGrid.removeAllViews();
        setClipChildren(false);
        setClipToPadding(false);

    }

    public void setCallback(KeyguardHostView.UserSwitcherCallback callback) {
        mCallback = callback;
    }

    public void addUsers(Collection<UserInfo> userList) {
        UserInfo activeUser;
        try {
            activeUser = ActivityManagerNative.getDefault().getCurrentUser();
        } catch (RemoteException re) {
            activeUser = null;
        }

        ArrayList<UserInfo> users = new ArrayList<UserInfo>(userList);
        Collections.sort(users, mOrderAddedComparator);

        for (UserInfo user: users) {
            KeyguardMultiUserAvatar uv = createAndAddUser(user);
            if (user.id == activeUser.id) {
                mActiveUserAvatar = uv;
            }
            uv.setActive(false, false, null);
        }
        mActiveUserAvatar.lockPressed(true);
    }

    public void finalizeActiveUserView(boolean animate) {
        if (animate) {
            getHandler().postDelayed(new Runnable() {
                    @Override
                        public void run() {
                        finalizeActiveUserNow(true);
                    }
                }, 500);
        } else {
            finalizeActiveUserNow(animate);
        }
    }

    void finalizeActiveUserNow(boolean animate) {
        mActiveUserAvatar.lockPressed(false);
        mActiveUserAvatar.setActive(true, animate, null);
    }

    Comparator<UserInfo> mOrderAddedComparator = new Comparator<UserInfo>() {
        @Override
        public int compare(UserInfo lhs, UserInfo rhs) {
            return (lhs.serialNumber - rhs.serialNumber);
        }
    };

    private KeyguardMultiUserAvatar createAndAddUser(UserInfo user) {
        KeyguardMultiUserAvatar uv = KeyguardMultiUserAvatar.fromXml(
                R.layout.keyguard_multi_user_avatar, mContext, this, user);
        mUsersGrid.addView(uv);
        return uv;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if(event.getActionMasked() != MotionEvent.ACTION_CANCEL && mCallback != null) {
            mCallback.userActivity();
        }
        return false;
    }

    private void setAllClickable(boolean clickable)
    {
        for(int i = 0; i < mUsersGrid.getChildCount(); i++) {
            View v = mUsersGrid.getChildAt(i);
            v.setClickable(clickable);
            v.setPressed(false);
        }
    }

    @Override
    public void onClick(View v) {
        if (!(v instanceof KeyguardMultiUserAvatar)) return;
        final KeyguardMultiUserAvatar avatar = (KeyguardMultiUserAvatar) v;
        if (avatar.isClickable()) { // catch race conditions
            if (mActiveUserAvatar == avatar) {
                // If they click the currently active user, show the unlock hint
                mCallback.showUnlockHint();
                return;
            }
            /// M: sdcard&otg only for owner @{
            else if (FeatureOption.MTK_OWNER_SDCARD_ONLY_SUPPORT&&(mActiveUserAvatar.getUserInfo().id == UserHandle.USER_OWNER)&&hasAppsAccessingSD()) {
                switchUserWithSd(avatar);                
            }           
            /// @}
            else {
                // Reset the previously active user to appear inactive
                mCallback.hideSecurityView(FADE_OUT_ANIMATION_DURATION);
                setAllClickable(false);
                avatar.lockPressed(true);
                mActiveUserAvatar.setActive(false, true, new Runnable() {
                    @Override
                    public void run() {
                        mActiveUserAvatar = avatar;
                        try {
                            ActivityManagerNative.getDefault()
                                    .switchUser(avatar.getUserInfo().id);
                        } catch (RemoteException re) {
                            Log.e(TAG, "Couldn't switch user " + re);
                        }
                    }
                });
            }
        }
    }

    /// M: MTK added function begin
    /// M: sdcard&otg only for owner @{
    private boolean hasAppsAccessingSD() {
        try {
            IMountService mountService = null;
            IBinder service = ServiceManager.getService("mount");
            if (service != null) {
                mountService = IMountService.Stub.asInterface(service);
            } else {
                Log.e(TAG, "Can't get mount service");
                return false;
            }
            StorageManager storageManager = (StorageManager) mContext.getSystemService(
                    Context.STORAGE_SERVICE);
            for (StorageVolume volume : mountService.getVolumeList()) {
                String path = volume.getPath();
                if (volume != null) {
                    String state = storageManager.getVolumeState(path);
                    Log.d(TAG, "check volume in list path = " + path + " state = " + state);
                    boolean isMounted = Environment.MEDIA_MOUNTED.equals(state);
                    if (volume.isRemovable() && isMounted) {
                        int[] users = mountService.getStorageUsers(path);
                        if (users != null && users.length > 0) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
      }
    }
    /// @}

    private void switchUserWithSd(final KeyguardMultiUserAvatar avatar){
        Log.e(TAG, "switch user from owner and using sd");
        AlertDialog.Builder bdl = new AlertDialog.Builder(getContext());
        bdl.setMessage(R.string.sd_accessing_swtich_user_message);
        bdl.setTitle(R.string.sd_accessing_swtich_user_title);
        bdl.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
    
        @Override
        public void onClick(DialogInterface arg0, int arg1) {
            return;
          }
        });
        bdl.setPositiveButton("OK", new DialogInterface.OnClickListener(){
    
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // Reset the previously active user to appear inactive
                mCallback.hideSecurityView(FADE_OUT_ANIMATION_DURATION);
                setAllClickable(false);
                mActiveUserAvatar.setActive(false, true, new Runnable() {
                    @Override
                    public void run() {
                        mActiveUserAvatar = avatar;
                        mActiveUserAvatar.setActive(true, true, new Runnable() {
                            @Override
                            public void run() {
                                    try {
                                        ActivityManagerNative.getDefault()
                                            .switchUser(avatar.getUserInfo().id);
                                    } catch (RemoteException re) {
                                        Log.e(TAG, "Couldn't switch user " + re);
                                    }
                            }
                        });
                    }
                });
            }
        });
        Dialog dlg = bdl.create();
        WindowManager.LayoutParams l = dlg.getWindow().getAttributes();
        l.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        l.token = getWindowToken();
        dlg.getWindow().setAttributes(l);
        dlg.show();
    }

    /// M: MTK added function end
}
