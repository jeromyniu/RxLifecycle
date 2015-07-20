/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.trello.rxlifecycle;

import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;

public class RxLifecycle {

    private RxLifecycle() {
        throw new AssertionError("No instances");
    }

    /**
     * Binds the given source to a lifecycle.
     * <p/>
     * Use with {@link Observable#compose(Observable.Transformer)}:
     * {@code source.compose(RxLifecycle.bindUntilLifecycleEvent(lifecycle, LifecycleEvent.STOP)).subscribe()}
     * <p/>
     * When the lifecycle event occurs, the source will cease to emit any notifications.
     * <p/>
     * Warning: In order for this to work in all possible cases, this should only be called
     * immediately before calling subscribe().
     *
     * @param lifecycle the lifecycle sequence
     * @param event the event which should conclude notifications from the source
     */
    public static <T> Observable.Transformer<T, T> bindUntilLifecycleEvent(final Observable<LifecycleEvent> lifecycle,
                                                                           final LifecycleEvent event) {
        if (lifecycle == null) {
            throw new IllegalArgumentException("Lifecycle must be given");
        }

        return new Observable.Transformer<T, T>() {
            @Override
            public Observable<T> call(Observable<T> source) {
                return source.lift(
                    new OperatorSubscribeUntil<T, LifecycleEvent>(
                        lifecycle.takeFirst(new Func1<LifecycleEvent, Boolean>() {
                            @Override
                            public Boolean call(LifecycleEvent lifecycleEvent) {
                                return lifecycleEvent == event;
                            }
                        })
                    )
                );
            }
        };
    }

    /**
     * Binds the given source to an Activity lifecycle.
     * <p/>
     * Use with {@link Observable#compose(Observable.Transformer)}:
     * {@code source.compose(RxLifecycle.bindActivityLifecycle(lifecycle)).subscribe()}
     * <p/>
     * This helper automatically determines (based on the lifecycle sequence itself) when the source
     * should stop emitting items. In the case that the lifecycle sequence is in the
     * creation phase (CREATE, START, etc) it will choose the equivalent destructive phase (DESTROY,
     * STOP, etc). If used in the destructive phase, the notifications will cease at the next event;
     * for example, if used in PAUSE, it will unsubscribe in STOP.
     * <p/>
     * Due to the differences between the Activity and Fragment lifecycles, this method should only
     * be used for an Activity lifecycle.
     * <p/>
     * Warning: In order for this to work in all possible cases, this should only be called
     * immediately before calling subscribe().
     *
     * @param lifecycle the lifecycle sequence of an Activity
     */
    public static <T> Observable.Transformer<T, T> bindActivityLifecycle(Observable<LifecycleEvent> lifecycle) {
        return bindLifecycle(lifecycle, ACTIVITY_LIFECYCLE);
    }

    /**
     * Binds the given source to a Fragment lifecycle.
     * <p/>
     * Use with {@link Observable#compose(Observable.Transformer)}:
     * {@code source.compose(RxLifecycle.bindFragmentLifecycle(lifecycle)).subscribe()}
     * <p/>
     * This helper automatically determines (based on the lifecycle sequence itself) when the source
     * should stop emitting items. In the case that the lifecycle sequence is in the
     * creation phase (CREATE, START, etc) it will choose the equivalent destructive phase (DESTROY,
     * STOP, etc). If used in the destructive phase, the notifications will cease at the next event;
     * for example, if used in PAUSE, it will unsubscribe in STOP.
     * <p/>
     * Due to the differences between the Activity and Fragment lifecycles, this method should only
     * be used for a Fragment lifecycle.
     * <p/>
     * Warning: In order for this to work in all possible cases, this should only be called
     * immediately before calling subscribe().
     *
     * @param lifecycle the lifecycle sequence of a Fragment
     */
    public static <T> Observable.Transformer<T, T> bindFragmentLifecycle(Observable<LifecycleEvent> lifecycle) {
        return bindLifecycle(lifecycle, FRAGMENT_LIFECYCLE);
    }

    private static <T> Observable.Transformer<T, T> bindLifecycle(Observable<LifecycleEvent> lifecycle,
                                                                  final Func1<LifecycleEvent, LifecycleEvent> correspondingEvents) {
        if (lifecycle == null) {
            throw new IllegalArgumentException("Lifecycle must be given");
        }

        // Make sure we're truly comparing a single stream to itself
        final Observable<LifecycleEvent> sharedLifecycle = lifecycle.share();

        // Keep emitting from source until the corresponding event occurs in the lifecycle
        return new Observable.Transformer<T, T>() {
            @Override
            public Observable<T> call(Observable<T> source) {
                return source.lift(
                    new OperatorSubscribeUntil<T, Boolean>(
                        Observable.combineLatest(
                            sharedLifecycle.take(1).map(correspondingEvents),
                            sharedLifecycle.skip(1),
                            new Func2<LifecycleEvent, LifecycleEvent, Boolean>() {
                                @Override
                                public Boolean call(LifecycleEvent bindUntilEvent, LifecycleEvent lifecycleEvent) {
                                    return lifecycleEvent == bindUntilEvent;
                                }
                            })
                            .takeFirst(new Func1<Boolean, Boolean>() {
                                @Override
                                public Boolean call(Boolean shouldComplete) {
                                    return shouldComplete;
                                }
                            })
                    )
                );
            }
        };
    }

    // Figures out which corresponding next lifecycle event in which to unsubscribe, for Activities
    private static final Func1<LifecycleEvent, LifecycleEvent> ACTIVITY_LIFECYCLE =
        new Func1<LifecycleEvent, LifecycleEvent>() {
            @Override
            public LifecycleEvent call(LifecycleEvent lastEvent) {
                if (lastEvent == null) {
                    throw new NullPointerException("Cannot bind to null LifecycleEvent.");
                }

                switch (lastEvent) {
                    case CREATE:
                        return LifecycleEvent.DESTROY;
                    case START:
                        return LifecycleEvent.STOP;
                    case RESUME:
                        return LifecycleEvent.PAUSE;
                    case PAUSE:
                        return LifecycleEvent.STOP;
                    case STOP:
                        return LifecycleEvent.DESTROY;
                    case DESTROY:
                        throw new IllegalStateException("Cannot bind to Activity lifecycle when outside of it.");
                    case ATTACH:
                    case CREATE_VIEW:
                    case DESTROY_VIEW:
                    case DETACH:
                        throw new IllegalStateException("Cannot bind to " + lastEvent + " for an Activity.");
                    default:
                        throw new UnsupportedOperationException("Binding to LifecycleEvent " + lastEvent
                            + " not yet implemented");
                }
            }
        };

    // Figures out which corresponding next lifecycle event in which to unsubscribe, for Fragments
    private static final Func1<LifecycleEvent, LifecycleEvent> FRAGMENT_LIFECYCLE =
        new Func1<LifecycleEvent, LifecycleEvent>() {
            @Override
            public LifecycleEvent call(LifecycleEvent lastEvent) {
                if (lastEvent == null) {
                    throw new NullPointerException("Cannot bind to null LifecycleEvent.");
                }

                switch (lastEvent) {
                    case ATTACH:
                        return LifecycleEvent.DETACH;
                    case CREATE:
                        return LifecycleEvent.DESTROY;
                    case CREATE_VIEW:
                        return LifecycleEvent.DESTROY_VIEW;
                    case START:
                        return LifecycleEvent.STOP;
                    case RESUME:
                        return LifecycleEvent.PAUSE;
                    case PAUSE:
                        return LifecycleEvent.STOP;
                    case STOP:
                        return LifecycleEvent.DESTROY_VIEW;
                    case DESTROY_VIEW:
                        return LifecycleEvent.DESTROY;
                    case DESTROY:
                        return LifecycleEvent.DETACH;
                    case DETACH:
                        throw new IllegalStateException("Cannot bind to Fragment lifecycle when outside of it.");
                    default:
                        throw new UnsupportedOperationException("Binding to LifecycleEvent " + lastEvent
                            + " not yet implemented");
                }
            }
        };
}
