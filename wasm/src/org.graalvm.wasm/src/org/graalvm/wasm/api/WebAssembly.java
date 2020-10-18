/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.wasm.api;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import org.graalvm.wasm.WasmContext;
import org.graalvm.wasm.exception.BinaryParserException;
import org.graalvm.wasm.exception.WasmJsApiException;

public class WebAssembly extends Dictionary {
    private final WasmContext currentContext;

    public WebAssembly(WasmContext currentContext) {
        this.currentContext = currentContext;
        addMember("compile", new Executable(args -> compile(args)));
        addMember("instantiate", new Executable(args -> instantiate(args)));
        addMember("validate", new Executable(args -> validate(args)));
    }

    private Object instantiate(Object[] args) {
        checkArgumentCount(args, 2);
        Object source = args[0];
        Object importObject = args[1];
        if (source instanceof Module) {
            return instantiate((Module) source, importObject);
        } else {
            return instantiate(toBytes(source), importObject);
        }
    }

    public WebAssemblyInstantiatedSource instantiate(byte[] source, Object importObject) {
        final Module module = compile(source);
        final Instance instance = instantiate(module, importObject);
        return new WebAssemblyInstantiatedSource(module, instance);
    }

    public Instance instantiate(Module module, Object importObject) {
        final TruffleContext innerTruffleContext = currentContext.environment().newContextBuilder().build();
        final Object prev = innerTruffleContext.enter(null);
        try {
            return new Instance(innerTruffleContext, module, importObject);
        } finally {
            innerTruffleContext.leave(null, prev);
        }
    }

    private Object compile(Object[] args) {
        checkArgumentCount(args, 1);
        return compile(toBytes(args[0]));
    }

    @SuppressWarnings("unused")
    public Module compile(byte[] source) {
        return new Module(currentContext, source);
    }

    private boolean validate(Object[] args) {
        checkArgumentCount(args, 1);
        return validate(toBytes(args[0]));
    }

    private boolean validate(byte[] bytes) {
        try {
            compile(bytes);
            return true;
        } catch (BinaryParserException ex) {
            return false;
        }
    }

    private static void checkArgumentCount(Object[] args, int requiredCount) {
        if (args.length < requiredCount) {
            throw new WasmJsApiException(WasmJsApiException.Kind.TypeError, "Insufficient number of arguments");
        }
    }

    private static byte[] toBytes(Object source) {
        InteropLibrary interop = InteropLibrary.getUncached(source);
        if (interop.hasArrayElements(source)) {
            try {
                long size = interop.getArraySize(source);
                if (size == (int) size) {
                    byte[] bytes = new byte[(int) size];
                    for (int i = 0; i < bytes.length; i++) {
                        Object element = interop.readArrayElement(source, i);
                        if (element instanceof Number) {
                            bytes[i] = ((Number) element).byteValue();
                        } else {
                            bytes[i] = InteropLibrary.getUncached(element).asByte(element);
                        }
                    }
                    return bytes;
                }
            } catch (InteropException iex) {
                throw cannotConvertToBytesError(iex);
            }
        }
        throw cannotConvertToBytesError(null);
    }

    private static WasmJsApiException cannotConvertToBytesError(Throwable cause) {
        WasmJsApiException.Kind kind = WasmJsApiException.Kind.TypeError;
        String message = "Cannot convert to bytes";
        return (cause == null) ? new WasmJsApiException(kind, message) : new WasmJsApiException(kind, message, cause);
    }

}
