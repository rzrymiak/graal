/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.tools.profiler;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;

import com.oracle.truffle.api.TruffleContext;
import com.oracle.truffle.api.instrumentation.ContextsListener;
import com.oracle.truffle.api.instrumentation.SourceSectionFilter;
import com.oracle.truffle.api.instrumentation.StandardTags.RootTag;
import com.oracle.truffle.api.instrumentation.TruffleInstrument;
import com.oracle.truffle.api.instrumentation.TruffleInstrument.Env;
import com.oracle.truffle.api.nodes.LanguageInfo;
import com.oracle.truffle.tools.profiler.SafepointStack.StackSample;
import com.oracle.truffle.tools.profiler.impl.CPUSamplerInstrument;
import com.oracle.truffle.tools.profiler.impl.ProfilerToolFactory;

/**
 * Implementation of a sampling based profiler for
 * {@linkplain com.oracle.truffle.api.TruffleLanguage Truffle languages} built on top of the
 * {@linkplain TruffleInstrument Truffle instrumentation framework}.
 * <p>
 * The sampler keeps a shadow stack during execution. This shadow stack is sampled at regular
 * intervals, i.e. the state of the stack is copied and saved into trees of {@linkplain ProfilerNode
 * nodes}, which represent the profile of the execution.
 * <p>
 * Usage example: {@codesnippet CPUSamplerSnippets#example}
 *
 * @since 0.30
 */
public final class CPUSampler implements Closeable {

    /**
     * Wrapper for information on how many times an element was seen on the shadow stack. Used as a
     * template parameter of {@link ProfilerNode}. Differentiates between an execution in compiled
     * code and in the interpreter.
     *
     * @since 0.30
     */
    public static final class Payload {

        Payload() {
        }

        int compiledHitCount;
        int interpretedHitCount;

        int selfCompiledHitCount;
        int selfInterpretedHitCount;

        final List<Long> selfHitTimes = new ArrayList<>();

        /**
         * @return The number of times the element was found bellow the top of the shadow stack as
         *         compiled code
         * @since 0.30
         */
        public int getCompiledHitCount() {
            return compiledHitCount;
        }

        /**
         * @return The number of times the element was found bellow the top of the shadow stack as
         *         interpreted code
         * @since 0.30
         */
        public int getInterpretedHitCount() {
            return interpretedHitCount;
        }

        /**
         * @return The number of times the element was found on the top of the shadow stack as
         *         compiled code
         * @since 0.30
         */
        public int getSelfCompiledHitCount() {
            return selfCompiledHitCount;
        }

        /**
         * @return The number of times the element was found on the top of the shadow stack as
         *         interpreted code
         * @since 0.30
         */
        public int getSelfInterpretedHitCount() {
            return selfInterpretedHitCount;
        }

        /**
         * @return Total number of times the element was found on the top of the shadow stack
         * @since 0.30
         */
        public int getSelfHitCount() {
            return selfCompiledHitCount + selfInterpretedHitCount;
        }

        /**
         * @return Total number of times the element was found bellow the top of the shadow stack
         * @since 0.30
         */
        public int getHitCount() {
            return compiledHitCount + interpretedHitCount;
        }

        /**
         * @return An immutable list of time stamps for the times that the element was on the top of
         *         the stack
         * @since 0.30
         */
        public List<Long> getSelfHitTimes() {
            return Collections.unmodifiableList(selfHitTimes);
        }

        void addSelfHitTime(Long time) {
            selfHitTimes.add(time);
        }
    }

    /**
     * Describes the different modes in which the CPU sampler can operate.
     *
     * @since 0.30
     */
    public enum Mode {
        /**
         * Sample {@link RootTag Roots} <b>excluding</b> the ones that get inlined during
         * compilation. This mode is the default and has the least amount of impact on peak
         * performance.
         *
         * @since 0.30
         */
        EXCLUDE_INLINED_ROOTS,
        /**
         * Sample {@link RootTag Roots} <b>including</b> the ones that get inlined during
         * compilation.
         *
         * @since 0.30
         */
        ROOTS,
        /**
         * Sample all {@link com.oracle.truffle.api.instrumentation.StandardTags.StatementTag
         * Statements}. This mode has serious impact on peek performance.
         *
         * @since 0.30
         */
        STATEMENTS
    }

    static final SourceSectionFilter DEFAULT_FILTER = SourceSectionFilter.newBuilder().tagIs(RootTag.class).build();

    private volatile boolean closed;

    private volatile boolean collecting;

    private long period = 10;

    private long delay = 0;

    private int stackLimit = 10000;

    private SourceSectionFilter filter = DEFAULT_FILTER;

    private Map<TruffleContext, AtomicLong> samplesTaken = new HashMap<>(0);

    private Timer samplerThread;

    private SamplingTimerTask samplerTask;

    private volatile SafepointStack safepointStack;

    private final Env env;

    private boolean gatherSelfHitTimes = false;

    private volatile boolean nonInternalLanguageContextInitialized = false;

    private boolean delaySamplingUntilNonInternalLangInit = true;

    private final Map<TruffleContext, Map<Thread, ProfilerNode<Payload>>> activeContexts = Collections.synchronizedMap(new HashMap<>());

    CPUSampler(Env env) {
        this.env = env;

        env.getInstrumenter().attachContextsListener(new ContextsListener() {

            @Override
            public void onContextCreated(TruffleContext context) {
                synchronized (CPUSampler.this) {
                    activeContexts.put(context, new LinkedHashMap<>());
                    samplesTaken.put(context, new AtomicLong(0));
                }
            }

            public void onLanguageContextCreate(TruffleContext context, LanguageInfo language) {
                pushSyntheticFrame(context, language, "CreateContext");
            }

            @Override
            public void onLanguageContextCreated(TruffleContext context, LanguageInfo language) {
                popSyntheticFrame(context, language);
            }

            public void onLanguageContextCreateFailed(TruffleContext context, LanguageInfo language) {
                popSyntheticFrame(context, language);
            }

            public void onLanguageContextInitialize(TruffleContext context, LanguageInfo language) {
                pushSyntheticFrame(context, language, "InitializeContext");
            }

            @Override
            public void onLanguageContextInitialized(TruffleContext context, LanguageInfo language) {
                popSyntheticFrame(context, language);
                if (!language.isInternal()) {
                    nonInternalLanguageContextInitialized = true;
                }
            }

            public void onLanguageContextInitializeFailed(TruffleContext context, LanguageInfo language) {
                popSyntheticFrame(context, language);
            }

            @Override
            public void onLanguageContextFinalized(TruffleContext context, LanguageInfo language) {
            }

            @Override
            public void onLanguageContextDisposed(TruffleContext context, LanguageInfo language) {
            }

            @Override
            public void onContextClosed(TruffleContext context) {
                // TODO GR-32021
            }
        }, true);
    }

    @SuppressWarnings("unused")
    private void pushSyntheticFrame(TruffleContext context, LanguageInfo info, String message) {
        // TODO GR-32022
    }

    @SuppressWarnings("unused")
    private void popSyntheticFrame(TruffleContext context, LanguageInfo info) {
        // TODO GR-32022
    }

    /**
     * Finds {@link CPUSampler} associated with given engine.
     *
     * @since 19.0
     */
    public static CPUSampler find(Engine engine) {
        return CPUSamplerInstrument.getSampler(engine);
    }

    /**
     * Controls whether the sampler is collecting data or not.
     *
     * @param collecting the new state of the sampler.
     * @since 0.30
     */
    public synchronized void setCollecting(boolean collecting) {
        if (this.collecting != collecting) {
            this.collecting = collecting;
            resetSampling();
        }
    }

    /**
     * @return whether or not the sampler is currently collecting data.
     * @since 0.30
     */
    public synchronized boolean isCollecting() {
        return collecting;
    }

    /**
     * Sets the {@link Mode mode} for the sampler.
     *
     * @param mode the new mode for the sampler.
     * @since 0.30
     */
    @SuppressWarnings("unused")
    @Deprecated
    public synchronized void setMode(Mode mode) {
        // Deprecated, a noop.
    }

    /**
     * Sets the sampling period i.e. the time between two samples of the shadow stack are taken.
     *
     * @param samplePeriod the new sampling period.
     * @since 0.30
     */
    public synchronized void setPeriod(long samplePeriod) {
        enterChangeConfig();
        if (samplePeriod < 1) {
            throw new ProfilerException(String.format("Invalid sample period %s.", samplePeriod));
        }
        this.period = samplePeriod;
    }

    /**
     * @return the sampling period i.e. the time between two samples of the shadow stack are taken.
     * @since 0.30
     */
    public synchronized long getPeriod() {
        return period;
    }

    /**
     * Sets the delay period i.e. the time that is allowed to pass between when the first sample
     * would have been taken and when the sampler actually starts taking samples.
     *
     * @param delay the delay period.
     * @since 0.30
     */
    public synchronized void setDelay(long delay) {
        enterChangeConfig();
        if (delay < 0) {
            throw new ProfilerException(String.format("Invalid delay %s.", delay));
        }
        this.delay = delay;
    }

    /**
     * Sets the maximum amount of stack frames that are sampled. Whether or not the stack grew more
     * than the provided size during execution can be checked with {@linkplain #hasStackOverflowed}
     *
     * @param stackLimit the new size of the shadow stack
     * @since 0.30
     */
    public synchronized void setStackLimit(int stackLimit) {
        enterChangeConfig();
        if (stackLimit < 1) {
            throw new ProfilerException(String.format("Invalid stack limit %s.", stackLimit));
        }
        this.stackLimit = stackLimit;
    }

    /**
     * @return size of the shadow stack
     * @since 0.30
     */
    public synchronized int getStackLimit() {
        return stackLimit;
    }

    /**
     * Sets the {@link SourceSectionFilter filter} for the sampler. The sampler will only observe
     * parts of the executed source code that is specified by the filter.
     *
     * @param filter The new filter describing which part of the source code to sample
     * @since 0.30
     */
    public synchronized void setFilter(SourceSectionFilter filter) {
        enterChangeConfig();
        this.filter = filter;
    }

    /**
     * Sets the option to delay sampling until a non-internal language is initialized. Useful to
     * avoid internal language initialisation code in the samples.
     *
     * @param delaySamplingUntilNonInternalLangInit Enable or disable this option.
     * @since 0.31
     */
    public synchronized void setDelaySamplingUntilNonInternalLangInit(boolean delaySamplingUntilNonInternalLangInit) {
        enterChangeConfig();
        this.delaySamplingUntilNonInternalLangInit = delaySamplingUntilNonInternalLangInit;
    }

    /**
     * @return The filter describing which part of the source code to sample
     * @since 0.30
     */
    public synchronized SourceSectionFilter getFilter() {
        return filter;
    }

    /**
     * @return Total number of samples taken during execution
     * @since 0.30
     */
    public long getSampleCount() {
        long sum = 0;
        for (AtomicLong value : samplesTaken.values()) {
            sum += value.get();
        }
        return sum;
    }

    /**
     * @return was the shadow stack size insufficient for the execution.
     * @since 0.30
     */
    public boolean hasStackOverflowed() {
        return safepointStack.hasOverflowed();
    }

    /**
     * Merges all the 'per thread' profiles into one set of nodes and returns it.
     *
     * @return The roots of the trees representing the profile of the execution.
     * @since 0.30
     */
    public synchronized Collection<ProfilerNode<Payload>> getRootNodes() {
        ProfilerNode<Payload> mergedRoot = new ProfilerNode<>();
        Map<Thread, Collection<ProfilerNode<Payload>>> threadToNodes = getThreadToNodesMap();
        for (Collection<ProfilerNode<Payload>> nodes : threadToNodes.values()) {
            for (ProfilerNode<Payload> node : nodes) {
                mergedRoot.deepMergeNodeToChildren(node, MERGE_PAYLOAD, PAYLOAD_FACTORY);
            }
        }
        return mergedRoot.getChildren();
    }

    private static final Supplier<Payload> PAYLOAD_FACTORY = new Supplier<Payload>() {
        @Override
        public Payload get() {
            return new Payload();
        }
    };

    private static final BiConsumer<Payload, Payload> MERGE_PAYLOAD = new BiConsumer<Payload, Payload>() {
        @Override
        public void accept(Payload sourcePayload, Payload destinationPayload) {
            destinationPayload.selfCompiledHitCount += sourcePayload.selfCompiledHitCount;
            destinationPayload.selfInterpretedHitCount += sourcePayload.selfInterpretedHitCount;
            destinationPayload.compiledHitCount += sourcePayload.compiledHitCount;
            destinationPayload.interpretedHitCount += sourcePayload.interpretedHitCount;
            for (Long timestamp : sourcePayload.getSelfHitTimes()) {
                destinationPayload.addSelfHitTime(timestamp);
            }
        }
    };

    private static final Function<Payload, Payload> COPY_PAYLOAD = new Function<Payload, Payload>() {
        @Override
        public Payload apply(Payload sourcePayload) {
            Payload destinationPayload = new Payload();
            destinationPayload.selfCompiledHitCount = sourcePayload.selfCompiledHitCount;
            destinationPayload.selfInterpretedHitCount = sourcePayload.selfInterpretedHitCount;
            destinationPayload.compiledHitCount = sourcePayload.compiledHitCount;
            destinationPayload.interpretedHitCount = sourcePayload.interpretedHitCount;
            for (Long timestamp : sourcePayload.getSelfHitTimes()) {
                destinationPayload.addSelfHitTime(timestamp);
            }
            return destinationPayload;
        }
    };

    /**
     * @return The roots of the trees representing the profile of the execution per thread.
     * @since 19.0
     */
    public synchronized Map<Thread, Collection<ProfilerNode<Payload>>> getThreadToNodesMap() {
        if (activeContexts.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Thread, Collection<ProfilerNode<Payload>>> returnValue = new HashMap<>();
        for (Map.Entry<Thread, ProfilerNode<Payload>> entry : activeContexts.values().iterator().next().entrySet()) {
            ProfilerNode<Payload> copy = new ProfilerNode<>();
            copy.deepCopyChildrenFrom(entry.getValue(), COPY_PAYLOAD);
            returnValue.put(entry.getKey(), copy.getChildren());
        }
        return Collections.unmodifiableMap(returnValue);
    }

    /**
     * Get per-context profiling data.
     *
     * @return a map from {@link TruffleContext} to {@link CPUSamplerData}.
     * @since 21.3.0
     */
    public synchronized Map<TruffleContext, CPUSamplerData> getContextData() {
        if (activeContexts.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<TruffleContext, CPUSamplerData> contextToData = new HashMap<>();
        for (Entry<TruffleContext, Map<Thread, ProfilerNode<Payload>>> contextEntry : this.activeContexts.entrySet()) {
            Map<Thread, Collection<ProfilerNode<Payload>>> threads = new HashMap<>();
            for (Map.Entry<Thread, ProfilerNode<Payload>> threadEntry : contextEntry.getValue().entrySet()) {
                ProfilerNode<Payload> copy = new ProfilerNode<>();
                copy.deepCopyChildrenFrom(threadEntry.getValue(), COPY_PAYLOAD);
                threads.put(threadEntry.getKey(), copy.getChildren());
            }
            TruffleContext context = contextEntry.getKey();
            contextToData.put(context, new CPUSamplerData(context, threads, samplerTask.biasStatistic, samplerTask.durationStatistic, samplesTaken.get(context).get(), period));
        }
        return contextToData;
    }

    /**
     * The sample bias is a measurement of of how much time passed between requesting a stack sample
     * and starting the stack traversal. This method provies a summary of said times during the
     * profiling run.
     *
     * @return A {@link LongSummaryStatistics} of the sample bias.
     * @since 21.3.0
     */
    public synchronized LongSummaryStatistics getBiasStatistics() {
        return samplerTask.biasStatistic;
    }

    /**
     * The sample duration is a measurement of how long it took to traverse the stack when taking a
     * sample. This method provides a summary of said times during the profiling run.
     *
     * @return A {@link LongSummaryStatistics} of the sample duration.
     * @since 21.3.0
     */
    public LongSummaryStatistics getSampleDuration() {
        return samplerTask.durationStatistic;
    }

    /**
     * Erases all the data gathered by the sampler and resets the sample count to 0.
     *
     * @since 0.30
     */
    public synchronized void clearData() {
        samplesTaken.clear();
        for (TruffleContext context : activeContexts.keySet()) {
            activeContexts.get(context).clear();
        }
    }

    /**
     * @return whether or not the sampler has collected any data so far.
     * @since 0.30
     */
    public synchronized boolean hasData() {
        for (AtomicLong value : samplesTaken.values()) {
            if (value != null && value.get() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Closes the sampler for further use, deleting all the gathered data.
     *
     * @since 0.30
     */
    @Override
    public synchronized void close() {
        closed = true;
        resetSampling();
        clearData();
    }

    /**
     * @return Whether or not timestamp information for the element at the top of the stack for each
     *         sample is gathered
     * @since 0.30
     */
    public boolean isGatherSelfHitTimes() {
        return gatherSelfHitTimes;
    }

    /**
     * Sets whether or not to gather timestamp information for the element at the top of the stack
     * for each sample.
     *
     * @param gatherSelfHitTimes new value for whether or not to gather timestamps
     * @since 0.30
     */
    public synchronized void setGatherSelfHitTimes(boolean gatherSelfHitTimes) {
        enterChangeConfig();
        this.gatherSelfHitTimes = gatherSelfHitTimes;
    }

    /**
     * Sample all threads and gather their current stack trace entries. The returned map and lists
     * are unmodifiable and represent atomic snapshots of the stack at the time when this method was
     * invoked. Only active threads are sampled. A thread is active if it has at least one entry on
     * the stack. The sampling is initialized if this method is invoked for the first time or
     * reinitialized if the configuration changes.
     *
     * @since 19.0
     */
    public Map<Thread, List<StackTraceEntry>> takeSample() {
        if (safepointStack == null) {
            this.safepointStack = new SafepointStack(stackLimit, filter);
        }
        if (delaySamplingUntilNonInternalLangInit && !nonInternalLanguageContextInitialized) {
            return Collections.emptyMap();
        }
        if (activeContexts.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Thread, List<StackTraceEntry>> stacks = new HashMap<>();
        TruffleContext context = activeContexts.keySet().iterator().next();
        List<StackSample> sample = safepointStack.sample(env, context);
        for (StackSample stackSample : sample) {
            stacks.put(stackSample.thread, stackSample.stack);
        }
        return Collections.unmodifiableMap(stacks);
    }

    private void resetSampling() {
        assert Thread.holdsLock(this);
        cleanup();

        if (!collecting || closed) {
            return;
        }
        if (samplerThread == null) {
            samplerThread = new Timer("Sampling thread", true);
        }
        this.safepointStack = new SafepointStack(stackLimit, filter);
        this.samplerTask = new SamplingTimerTask();
        this.samplerThread.schedule(samplerTask, delay, period);
    }

    private void cleanup() {
        assert Thread.holdsLock(this);
        if (samplerTask != null) {
            samplerTask.cancel();
            samplerTask = null;
        }
        if (samplerThread != null) {
            samplerThread.cancel();
            samplerThread = null;
        }
    }

    private void enterChangeConfig() {
        assert Thread.holdsLock(this);
        if (closed) {
            throw new ProfilerException("CPUSampler is already closed.");
        } else if (collecting) {
            throw new ProfilerException("Cannot change sampler configuration while collecting. Call setCollecting(false) to disable collection first.");
        }
    }

    private class SamplingTimerTask extends TimerTask {

        private final LongSummaryStatistics biasStatistic = new LongSummaryStatistics();
        private final LongSummaryStatistics durationStatistic = new LongSummaryStatistics();

        @Override
        public void run() {
            if (delaySamplingUntilNonInternalLangInit && !nonInternalLanguageContextInitialized) {
                return;
            }

            long timestamp = System.currentTimeMillis();
            TruffleContext[] contexts;
            synchronized (CPUSampler.this) {
                contexts = activeContexts.keySet().toArray(new TruffleContext[activeContexts.size()]);
            }

            context: for (TruffleContext context : contexts) {
                if (context.isClosed()) {
                    continue;
                }

                List<StackSample> samples = safepointStack.sample(env, context);

                synchronized (CPUSampler.this) {
                    if (context.isClosed()) {
                        continue;
                    }
                    Map<Thread, ProfilerNode<Payload>> nodes = activeContexts.get(context);
                    if (nodes == null) {
                        continue context;
                    }

                    for (StackSample sample : samples) {
                        biasStatistic.accept(sample.biasNs);
                        durationStatistic.accept(sample.durationNs);

                        ProfilerNode<Payload> threadNode = nodes.computeIfAbsent(sample.thread, new Function<Thread, ProfilerNode<Payload>>() {
                            @Override
                            public ProfilerNode<Payload> apply(Thread thread) {
                                return new ProfilerNode<>();
                            }
                        });
                        sample(context, sample, threadNode, timestamp);
                    }
                }
            }

        }

        private void sample(TruffleContext context, StackSample sample, ProfilerNode<Payload> threadNode, long timestamp) {
            synchronized (CPUSampler.this) {
                // now traverse the stack and insert the path into the tree
                ProfilerNode<Payload> treeNode = threadNode;

                for (int i = sample.stack.size() - 1; i >= 0; i--) {
                    StackTraceEntry location = sample.stack.get(i);
                    boolean isCompiled = location.isCompiled();
                    treeNode = addOrUpdateChild(treeNode, location);
                    Payload payload = treeNode.getPayload();
                    if (i == 0) {
                        if (isCompiled) {
                            payload.selfCompiledHitCount++;
                        } else {
                            payload.selfInterpretedHitCount++;
                        }
                        if (gatherSelfHitTimes) {
                            payload.selfHitTimes.add(timestamp);
                            assert payload.selfHitTimes.size() == payload.getSelfHitCount();
                        }
                    }
                    if (isCompiled) {
                        payload.compiledHitCount++;
                    } else {
                        payload.interpretedHitCount++;
                    }
                }
            }
            samplesTaken.get(context).incrementAndGet();
        }

        private ProfilerNode<Payload> addOrUpdateChild(ProfilerNode<Payload> treeNode, StackTraceEntry location) {
            ProfilerNode<Payload> child = treeNode.findChild(location);
            if (child == null) {
                Payload payload = new Payload();
                child = new ProfilerNode<>(treeNode, location, payload);
                treeNode.addChild(location, child);
            }
            return child;
        }
    }

    static {
        CPUSamplerInstrument.setFactory(new ProfilerToolFactory<CPUSampler>() {
            @Override
            public CPUSampler create(Env env) {
                return new CPUSampler(env);
            }
        });
    }

}

class CPUSamplerSnippets {

    @SuppressWarnings("unused")
    public void example() {
        // @formatter:off
        // BEGIN: CPUSamplerSnippets#example
        Context context = Context.create();

        CPUSampler sampler = CPUSampler.find(context.getEngine());
        sampler.setCollecting(true);
        context.eval("...", "...");
        sampler.setCollecting(false);
        sampler.close();
        // Read information about the roots of the tree per thread.
        for (Collection<ProfilerNode<CPUSampler.Payload>> nodes
                : sampler.getThreadToNodesMap().values()) {
            for (ProfilerNode<CPUSampler.Payload> node : nodes) {
                final String rootName = node.getRootName();
                final int selfHitCount = node.getPayload().getSelfHitCount();
                // ...
            }
        }
        // END: CPUSamplerSnippets#example
        // @formatter:on
    }
}
