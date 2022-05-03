/*
 * Copyright (c) 2016-present, RxJava Contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.rxjava3.internal.operators.observable;

import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.internal.disposables.DisposableHelper;

//
public final class ObservableSubscribeOn<T> extends AbstractObservableWithUpstream<T, T> {
    final Scheduler scheduler;

    public ObservableSubscribeOn(ObservableSource<T> source, Scheduler scheduler) {
        super(source);
        this.scheduler = scheduler;
    }

    @Override
    public void subscribeActual(final Observer<? super T> observer) {
        final SubscribeOnObserver<T> parent = new SubscribeOnObserver<>(observer);

        // 给下游传递当前的Disposable
        observer.onSubscribe(parent);

        // new SubscribeTask(parent)为线程切换后需要执行的task
        // scheduleDirect 方法用于切换线程，同时返回一个 Disposable
        // 如果切换还没有完成，下游就调用了 dispose 方法，那么就应该取消执行scheduleDirect
        // parent.setDisposable 设置当前的 Disposable，给下游调用
        parent.setDisposable(scheduler.scheduleDirect(new SubscribeTask(parent)));
    }

    // 由于涉及到线程的切换，在不同的线程，Disposable应该是不同的
    // 所以继承了AtomicReference<Disposable>
    //
    // SubscribeOnObserver 是一个 Disposable，使下游可以调用 dispose 方法
    // 下游调用 dispose 方法以后，
    // 需要 dispose 线程切换
    // 需要 dispose 上游
    static final class SubscribeOnObserver<T> extends AtomicReference<Disposable> implements Observer<T>, Disposable {

        private static final long serialVersionUID = 8094547886072529208L;
        final Observer<? super T> downstream;

        final AtomicReference<Disposable> upstream;

        SubscribeOnObserver(Observer<? super T> downstream) {
            this.downstream = downstream;
            this.upstream = new AtomicReference<>();
        }

        @Override
        public void onSubscribe(Disposable d) {
            // 管理上游的Disposable，如果下游调用了 dispose，则也需要调用上游的 dispose方法
            DisposableHelper.setOnce(this.upstream, d);
        }

        @Override
        public void onNext(T t) {
            downstream.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            downstream.onComplete();
        }

        @Override
        public void dispose() {
            // 下游调用dispose后
            // 先调用上游的dispose
            // 在调用当前的dispose
            DisposableHelper.dispose(upstream);
            DisposableHelper.dispose(this);
        }

        @Override
        public boolean isDisposed() {
            // 只需要判断线程切换有没有被 isDisposed
            // 不需要判断上游的 isDisposed
            return DisposableHelper.isDisposed(get());
        }

        // 设置线程切换的 Disposable
        void setDisposable(Disposable d) {
            DisposableHelper.setOnce(this, d);
        }
    }

    // 在指定的线程，调用上游的subscribe
    // 这样上游的运行线程就被切换了
    final class SubscribeTask implements Runnable {
        private final SubscribeOnObserver<T> parent;

        SubscribeTask(SubscribeOnObserver<T> parent) {
            this.parent = parent;
        }

        @Override
        public void run() {
            source.subscribe(parent);
        }
    }
}
