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
package org.graalvm.compiler.nodes.calc;

import static org.graalvm.compiler.nodeinfo.NodeCycles.CYCLES_UNKNOWN;
import static org.graalvm.compiler.nodeinfo.NodeSize.SIZE_64;

import org.graalvm.compiler.core.common.type.IntegerStamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool.CharsetName;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.FixedWithNextNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;

/**
 * Intrinsification for {@code java.lang.StringCoding.implEncodeISOArray} and
 * {@code java.lang.StringCoding.implEncodeAsciiArray}. It encodes the provided byte/char array with
 * the specified encoding and stores the result into a distinct array.
 */
@NodeInfo(cycles = CYCLES_UNKNOWN, cyclesRationale = "Cannot estimate the time of a loop", size = SIZE_64)
public final class EncodeArrayNode extends FixedWithNextNode implements LIRLowerable {
    public static final NodeClass<EncodeArrayNode> TYPE = NodeClass.create(EncodeArrayNode.class);

    @Input protected ValueNode src;
    @Input protected ValueNode dst;
    @Input protected ValueNode len;

    private final CharsetName charset;

    public EncodeArrayNode(ValueNode src, ValueNode dst, ValueNode len, CharsetName charset) {
        super(TYPE, StampFactory.forInteger(32, 0,
                        ((IntegerStamp) len.stamp(NodeView.DEFAULT)).upperBound()));
        this.src = src;
        this.dst = dst;
        this.len = len;
        this.charset = charset;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        gen.setResult(this, gen.getLIRGeneratorTool().emitEncodeArray(gen.operand(src), gen.operand(dst), gen.operand(len), charset));
    }
}
