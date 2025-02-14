/* Copyright Statement:
 *
 * This software/firmware and related documentation ("MediaTek Software") are
 * protected under relevant copyright laws. The information contained herein
 * is confidential and proprietary to MediaTek Inc. and/or its licensors.
 * Without the prior written permission of MediaTek inc. and/or its licensors,
 * any reproduction, modification, use or disclosure of MediaTek Software,
 * and information contained herein, in whole or in part, shall be strictly prohibited.
 *
 * MediaTek Inc. (C) 2010. All rights reserved.
 *
 * BY OPENING THIS FILE, RECEIVER HEREBY UNEQUIVOCALLY ACKNOWLEDGES AND AGREES
 * THAT THE SOFTWARE/FIRMWARE AND ITS DOCUMENTATIONS ("MEDIATEK SOFTWARE")
 * RECEIVED FROM MEDIATEK AND/OR ITS REPRESENTATIVES ARE PROVIDED TO RECEIVER ON
 * AN "AS-IS" BASIS ONLY. MEDIATEK EXPRESSLY DISCLAIMS ANY AND ALL WARRANTIES,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR NONINFRINGEMENT.
 * NEITHER DOES MEDIATEK PROVIDE ANY WARRANTY WHATSOEVER WITH RESPECT TO THE
 * SOFTWARE OF ANY THIRD PARTY WHICH MAY BE USED BY, INCORPORATED IN, OR
 * SUPPLIED WITH THE MEDIATEK SOFTWARE, AND RECEIVER AGREES TO LOOK ONLY TO SUCH
 * THIRD PARTY FOR ANY WARRANTY CLAIM RELATING THERETO. RECEIVER EXPRESSLY ACKNOWLEDGES
 * THAT IT IS RECEIVER'S SOLE RESPONSIBILITY TO OBTAIN FROM ANY THIRD PARTY ALL PROPER LICENSES
 * CONTAINED IN MEDIATEK SOFTWARE. MEDIATEK SHALL ALSO NOT BE RESPONSIBLE FOR ANY MEDIATEK
 * SOFTWARE RELEASES MADE TO RECEIVER'S SPECIFICATION OR TO CONFORM TO A PARTICULAR
 * STANDARD OR OPEN FORUM. RECEIVER'S SOLE AND EXCLUSIVE REMEDY AND MEDIATEK'S ENTIRE AND
 * CUMULATIVE LIABILITY WITH RESPECT TO THE MEDIATEK SOFTWARE RELEASED HEREUNDER WILL BE,
 * AT MEDIATEK'S OPTION, TO REVISE OR REPLACE THE MEDIATEK SOFTWARE AT ISSUE,
 * OR REFUND ANY SOFTWARE LICENSE FEES OR SERVICE CHARGE PAID BY RECEIVER TO
 * MEDIATEK FOR SUCH MEDIATEK SOFTWARE AT ISSUE.
 *
 * The following software/firmware and/or related documentation ("MediaTek Software")
 * have been modified by MediaTek Inc. All revisions are subject to any receiver's
 * applicable license agreements with MediaTek Inc.
 */

/*
 * Copyright (C) 2010 The Android Open Source Project
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
//#define LOG_NDEBUG 0
#define LOG_TAG "AH263Assembler"
#include <utils/Log.h>

#include "AH263Assembler.h"

#include "ARTPSource.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/Utils.h>

namespace android {

AH263Assembler::AH263Assembler(const sp<AMessage> &notify)
    : mNotifyMsg(notify),
      mAccessUnitRTPTime(0),
      mNextExpectedSeqNoValid(false),
      mNextExpectedSeqNo(0),
#ifndef ANDROID_DEFAULT_CODE 
      mPReceived(false),
#endif // #ifndef ANDROID_DEFAULT_CODE
      mAccessUnitDamaged(false) {
}

AH263Assembler::~AH263Assembler() {
}

ARTPAssembler::AssemblyStatus AH263Assembler::assembleMore(
        const sp<ARTPSource> &source) {
    AssemblyStatus status = addPacket(source);
    if (status == MALFORMED_PACKET) {
        mAccessUnitDamaged = true;
    }
    return status;
}

ARTPAssembler::AssemblyStatus AH263Assembler::addPacket(
        const sp<ARTPSource> &source) {
    List<sp<ABuffer> > *queue = source->queue();

    if (queue->empty()) {
        return NOT_ENOUGH_DATA;
    }

    if (mNextExpectedSeqNoValid) {
        List<sp<ABuffer> >::iterator it = queue->begin();
        while (it != queue->end()) {
            if ((uint32_t)(*it)->int32Data() >= mNextExpectedSeqNo) {
                break;
            }

            it = queue->erase(it);
        }

        if (queue->empty()) {
            return NOT_ENOUGH_DATA;
        }
    }

    sp<ABuffer> buffer = *queue->begin();

    if (!mNextExpectedSeqNoValid) {
        mNextExpectedSeqNoValid = true;
        mNextExpectedSeqNo = (uint32_t)buffer->int32Data();
    } else if ((uint32_t)buffer->int32Data() != mNextExpectedSeqNo) {
#if VERBOSE
        LOG(VERBOSE) << "Not the sequence number I expected";
#endif

#ifndef ANDROID_DEFAULT_CODE 
        return getAssembleStatus(queue, mNextExpectedSeqNo);
#else
        return WRONG_SEQUENCE_NUMBER;
#endif // #ifndef ANDROID_DEFAULT_CODE
    }

    uint32_t rtpTime;
    CHECK(buffer->meta()->findInt32("rtp-time", (int32_t *)&rtpTime));

    if (mPackets.size() > 0 && rtpTime != mAccessUnitRTPTime) {
        submitAccessUnit();
    }
    mAccessUnitRTPTime = rtpTime;

    // hexdump(buffer->data(), buffer->size());

    if (buffer->size() < 2) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;

        return MALFORMED_PACKET;
    }

    unsigned payloadHeader = U16_AT(buffer->data());
#ifndef ANDROID_DEFAULT_CODE 
    // parse PLEN, VCR, PEBIT
    // return MALFORMED_PACKET instead of failure on RR!=0 or PEBIT!=0
    if ((payloadHeader >> 11) || (payloadHeader & 7)) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;
        if ((payloadHeader & 7))
            ALOGW("AH263Assembler does not support PEBIT yet");
        return MALFORMED_PACKET;
    }
#else
    CHECK_EQ(payloadHeader >> 11, 0u);  // RR=0
#endif
    unsigned P = (payloadHeader >> 10) & 1;
    unsigned V = (payloadHeader >> 9) & 1;
    unsigned PLEN = (payloadHeader >> 3) & 0x3f;
    unsigned PEBIT = payloadHeader & 7;

#ifdef ANDROID_DEFAULT_CODE
    // V=0
    if (V != 0u) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;
        ALOGW("Packet discarded due to VRC (V != 0)");
        return MALFORMED_PACKET;
    }

    // PLEN=0
    if (PLEN != 0u) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;
        ALOGW("Packet discarded (PLEN != 0)");
        return MALFORMED_PACKET;
    }

    // PEBIT=0
    if (PEBIT != 0u) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;
        ALOGW("Packet discarded (PEBIT != 0)");
        return MALFORMED_PACKET;
    }
#endif

    size_t skip = V + PLEN + (P ? 0 : 2);

    buffer->setRange(buffer->offset() + skip, buffer->size() - skip);

    if (P) {
        buffer->data()[0] = 0x00;
        buffer->data()[1] = 0x00;
#ifndef ANDROID_DEFAULT_CODE 
        mPReceived = true;
    } else if (!mPReceived) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;
        ALOGW("ignore packet before P");
        return OK;
#endif // #ifndef ANDROID_DEFAULT_CODE
    }

    mPackets.push_back(buffer);

    queue->erase(queue->begin());
    ++mNextExpectedSeqNo;

    return OK;
}

void AH263Assembler::submitAccessUnit() {
    CHECK(!mPackets.empty());

#if VERBOSE
    LOG(VERBOSE) << "Access unit complete (" << mPackets.size() << " packets)";
#endif

    size_t totalSize = 0;
    List<sp<ABuffer> >::iterator it = mPackets.begin();
    while (it != mPackets.end()) {
        const sp<ABuffer> &unit = *it;

        totalSize += unit->size();
        ++it;
    }

    sp<ABuffer> accessUnit = new ABuffer(totalSize);
    size_t offset = 0;
    it = mPackets.begin();
    while (it != mPackets.end()) {
        const sp<ABuffer> &unit = *it;

        memcpy((uint8_t *)accessUnit->data() + offset,
               unit->data(), unit->size());

        offset += unit->size();

        ++it;
    }

    CopyTimes(accessUnit, *mPackets.begin());

#if 0
    printf(mAccessUnitDamaged ? "X" : ".");
    fflush(stdout);
#endif

    if (mAccessUnitDamaged) {
        accessUnit->meta()->setInt32("damaged", true);
    }

    mPackets.clear();
    mAccessUnitDamaged = false;

    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setBuffer("access-unit", accessUnit);
    msg->post();
}

void AH263Assembler::packetLost() {
    CHECK(mNextExpectedSeqNoValid);
    ++mNextExpectedSeqNo;

    mAccessUnitDamaged = true;
}

void AH263Assembler::onByeReceived() {
    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setInt32("eos", true);
    msg->post();
}

}  // namespace android

