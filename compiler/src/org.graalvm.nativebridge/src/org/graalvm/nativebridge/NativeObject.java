/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.nativebridge;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongPredicate;

/**
 * Encapsulates a handle to an object in a native image heap where the object's lifetime is bound to
 * the lifetime of the {@link NativeObject} instance. At some point, after a {@link NativeObject} is
 * garbage collected, a call is made to release the handle, allowing the corresponding object in the
 * native image heap to be collected.
 */
public class NativeObject {

    private final NativeIsolate isolate;
    private final long objectHandle;
    private final CleanupAction cleanupAction;

    /**
     * Creates a new {@link NativeObject}.
     *
     * @param isolate an isolate in which an object referenced by the handle exists.
     * @param objectHandle a handle to an object in a native image heap
     */
    public NativeObject(NativeIsolate isolate, long objectHandle) {
        this.isolate = isolate;
        this.objectHandle = objectHandle;
        this.cleanupAction = new CleanupAction(objectHandle, isolate.getConfig());
        isolate.registerForCleanup(this, cleanupAction);
    }

    /**
     * Returns an isolate in which an object referenced by this handle exists.
     */
    public final NativeIsolate getIsolate() {
        return isolate;
    }

    /**
     * Returns a handle to an object in the native image heap.
     */
    public final long getHandle() {
        return objectHandle;
    }

    /**
     * Explicitly releases object in the native image heap referenced by this handle. The use of
     * this method should be exceptional. By default, the lifetime of the object in the native image
     * heap is bound to the lifetime of the {@link NativeObject} instance.
     */
    public final void release() {
        if (!cleanupAction.released.get()) {
            NativeIsolateThread nativeIsolateThread = isolate.enter();
            try {
                cleanupAction.test(nativeIsolateThread.getIsolateThreadId());
            } finally {
                nativeIsolateThread.leave();
            }
        }
    }

    static final class CleanupAction implements LongPredicate {

        private final long handle;
        private final JNIConfig config;
        private final AtomicBoolean released;

        CleanupAction(long handle, JNIConfig config) {
            this.handle = handle;
            this.config = config;
            this.released = new AtomicBoolean();
        }

        @Override
        public boolean test(long isolateThread) {
            if (released.compareAndSet(false, true)) {
                return config.releaseNativeObject(isolateThread, handle);
            } else {
                return true;
            }
        }

        @Override
        public String toString() {
            return "NativeObject 0x" + Long.toHexString(handle);
        }
    }
}
