/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package com.oracle.truffle.espresso.nodes;

import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.espresso.impl.Method;
import com.oracle.truffle.espresso.substitutions.CallableFromNative;

public class IntrinsifiedNativeMethodNode extends EspressoMethodNode {
    @Child private CallableFromNative nativeMethod;
    private final Object env;
    private final boolean prependClass;

    public IntrinsifiedNativeMethodNode(CallableFromNative.Factory factory, Method method, Object env) {
        super(method.getMethodVersion());
        this.nativeMethod = insert(factory.create(getMeta()));
        this.env = env;
        this.prependClass = shouldPrependClass(factory, method);
    }

    @Override
    Object executeBody(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        if (prependClass) {
            Method method = getMethod();
            int parameterCount = method.getParameterCount();
            Object[] newArgs = new Object[parameterCount + 1];
            newArgs[0] = method.getDeclaringKlass().mirror();
            System.arraycopy(args, 0, newArgs, 1, parameterCount);
            args = newArgs;
        }
        return nativeMethod.invokeDirect(env, args);
    }

    @Override
    void initializeBody(VirtualFrame frame) {
        // nop
    }

    @Override
    public int getBci(Frame frame) {
        return -2;
    }

    /* Static native methods may prepend the Class in the arg array */
    private static boolean shouldPrependClass(CallableFromNative.Factory factory, Method method) {
        return method.isStatic() && (factory.parameterCount()) == method.getParameterCount() + 1;
    }

    public static boolean validParameterCount(CallableFromNative.Factory factory, Method method) {
        return factory.parameterCount() == method.getParameterCount() || shouldPrependClass(factory, method);
    }
}
