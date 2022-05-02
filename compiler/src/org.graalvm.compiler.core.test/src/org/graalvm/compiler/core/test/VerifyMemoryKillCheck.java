/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.core.test;

import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.GuardNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.memory.MemoryKill;
import org.graalvm.compiler.nodes.memory.MultiMemoryKill;
import org.graalvm.compiler.nodes.memory.SingleMemoryKill;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.phases.VerifyPhase;

import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * Verifies that every typecheck of a {@link MemoryKill} goes through methods also checking if the
 * kill actually is a kill.
 */
public class VerifyMemoryKillCheck extends VerifyPhase<CoreProviders> {

    private static final Class<?>[] FORBIDDEN_MEMORYKILL_CHECKS = {
                    MemoryKill.class,
                    SingleMemoryKill.class,
                    MultiMemoryKill.class
    };

    @Override
    public boolean checkContract() {
        return false;
    }

    @Override
    protected void verify(StructuredGraph graph, CoreProviders context) {
        if (graph.method() != null && graph.method().getDeclaringClass().equals(context.getMetaAccess().lookupJavaType(MemoryKill.class))) {
            return;
        }
        final ResolvedJavaType[] bailoutType = new ResolvedJavaType[FORBIDDEN_MEMORYKILL_CHECKS.length];
        for (int i = 0; i < FORBIDDEN_MEMORYKILL_CHECKS.length; i++) {
            bailoutType[i] = context.getMetaAccess().lookupJavaType(FORBIDDEN_MEMORYKILL_CHECKS[i]);
        }
        ResolvedJavaMethod method = graph.method();
        ResolvedJavaType declaringClass = method.getDeclaringClass();
        if (!isTrustedInterface(declaringClass, context.getMetaAccess())) {

            outer: for (InstanceOfNode io : graph.getNodes().filter(InstanceOfNode.class)) {
                for (Node usage : io.usages()) {
                    if (usage instanceof GuardNode) {
                        GuardNode g = (GuardNode) usage;
                        if (g.getReason() == DeoptimizationReason.ClassCastException) {
                            continue outer;
                        }
                    } else if (usage instanceof FixedGuardNode) {
                        FixedGuardNode g = (FixedGuardNode) usage;
                        if (g.getReason() == DeoptimizationReason.ClassCastException) {
                            continue outer;
                        }
                    }
                }
                ResolvedJavaType type = io.type().getType();
                for (ResolvedJavaType forbiddenType : bailoutType) {
                    if (forbiddenType.equals(type)) {
                        String name = forbiddenType.getUnqualifiedName();
                        // strip outer class
                        ResolvedJavaType enclosingType = forbiddenType.getEnclosingType();
                        if (enclosingType != null) {
                            name = name.substring(enclosingType.getUnqualifiedName().length() + "$".length());
                        }
                        throw new VerificationError("Using `op instanceof %s` is not allowed. Use `MemoryKill.is%s(op)` instead. (in %s)", name, name, method.format("%H.%n(%p)"));
                    }
                }
            }
        }
    }

    private static boolean isTrustedInterface(ResolvedJavaType declaringClass, MetaAccessProvider metaAccess) {
        for (Class<?> trustedCls : FORBIDDEN_MEMORYKILL_CHECKS) {
            ResolvedJavaType trusted = metaAccess.lookupJavaType(trustedCls);
            if (trusted.equals(declaringClass)) {
                return true;
            }
        }
        return false;
    }

}
