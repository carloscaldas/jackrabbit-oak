/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.spi.commit;

import java.util.Arrays;
import java.util.Collection;

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;

/**
 * Composite commit hook. Maintains a list of component hooks and takes
 * care of calling them in proper sequence.
 */
public class CompositeHook implements CommitHook {

    public static CommitHook compose(@NotNull Collection<CommitHook> hooks) {
        System.out.println("@@@ CompositeHook.compose: [" + hooks.size() + "]");
        switch (hooks.size()) {
            case 0:
                return EmptyHook.INSTANCE;
            case 1:
                return hooks.iterator().next();
            default:
                System.out.println("@@@ CompositeHook.compose#3");
                return new CompositeHook(hooks);
        }
    }

    private final Collection<CommitHook> hooks;

    private CompositeHook(@NotNull Collection<CommitHook> hooks) {
        this.hooks = hooks;
    }

    public CompositeHook(CommitHook... hooks) {
        this(Arrays.asList(hooks));
    }

    @NotNull
    @Override
    public NodeState processCommit(
            NodeState before, NodeState after, CommitInfo info)
            throws CommitFailedException {
        System.out.println("@@@ CompositeHook.processCommit#1");
        NodeState newState = after;
        System.out.println("@@@ CompositeHook.processCommit#2");
        for (CommitHook hook : hooks) {
            System.out.println("@@@ CompositeHook.processCommit#3");
            newState = hook.processCommit(before, newState, info);
            System.out.println("@@@ CompositeHook.processCommit#4");
        }
        return newState;
    }

}
