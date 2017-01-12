package com.marverenic.music.player;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.RemoteException;

import com.marverenic.music.IPlayerService;
import com.marverenic.music.JockeyApplication;
import com.marverenic.music.data.store.ImmutablePreferenceStore;
import com.marverenic.music.data.store.PreferenceStore;
import com.marverenic.music.data.store.ReadOnlyPreferenceStore;
import com.marverenic.music.model.Song;
import com.marverenic.music.utils.ObservableQueue;
import com.marverenic.music.utils.Optional;
import com.marverenic.music.utils.Util;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;
import rx.subjects.PublishSubject;
import timber.log.Timber;

public class ServicePlayerController implements PlayerController {

    private static final int POSITION_TICK_MS = 200;

    private Context mContext;
    private IPlayerService mBinding;

    private PublishSubject<String> mErrorStream = PublishSubject.create();
    private PublishSubject<String> mInfoStream = PublishSubject.create();

    private final Prop<Boolean> mPlaying = new Prop<>("playing");
    private final Prop<Song> mNowPlaying = new Prop<>("now playing");
    private final Prop<List<Song>> mQueue = new Prop<>("queue");
    private final Prop<Integer> mQueuePosition = new Prop<>("queue index");
    private final Prop<Integer> mCurrentPosition = new Prop<>("seek position");
    private final Prop<Integer> mDuration = new Prop<>("duration");
    private final Prop<Integer> mMultiRepeatCount = new Prop<>("multi-repeat");
    private final Prop<Long> mSleepTimerEndTime = new Prop<>("sleep timer");

    private BehaviorSubject<Boolean> mShuffled;
    private BehaviorSubject<Bitmap> mArtwork;
    private Subscription mCurrentPositionClock;

    private ObservableQueue<Runnable> mRequestQueue;
    private Subscription mRequestQueueSubscription;

    public ServicePlayerController(Context context, PreferenceStore preferenceStore) {
        mContext = context;
        mShuffled = BehaviorSubject.create(preferenceStore.isShuffled());
        mRequestQueue = new ObservableQueue<>();

        startService();

        isPlaying().subscribe(
                isPlaying -> {
                    if (isPlaying) {
                        startCurrentPositionClock();
                    } else {
                        stopCurrentPositionClock();
                    }
                }, throwable -> {
                    Timber.e(throwable, "Failed to update current position clock");
                });
    }

    private void startService() {
        Intent serviceIntent = new Intent(mContext, PlayerService.class);

        // Manually start the service to ensure that it is associated with this task and can
        // appropriately set its dismiss behavior
        mContext.startService(serviceIntent);

        mContext.bindService(serviceIntent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mBinding = IPlayerService.Stub.asInterface(service);
                initAllProperties();
                bindRequestQueue();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // TODO restart service after crash
                mBinding = null;
                if (mRequestQueueSubscription != null) {
                    mRequestQueueSubscription.unsubscribe();
                    mRequestQueueSubscription = null;
                }
            }
        }, Context.BIND_WAIVE_PRIORITY);
    }

    private void bindRequestQueue() {
        mRequestQueueSubscription = mRequestQueue.toObservable()
                .subscribe(Runnable::run, throwable -> {
                    Timber.e(throwable, "Failed to process request");
                    // Make sure to restart the request queue, otherwise all future commands will
                    // be dropped
                    bindRequestQueue();
                });
    }

    private void execute(Runnable command) {
        mRequestQueue.enqueue(command);
    }

    private void startCurrentPositionClock() {
        if (mCurrentPositionClock != null && !mCurrentPositionClock.isUnsubscribed()) {
            return;
        }

        mCurrentPositionClock = Observable.interval(POSITION_TICK_MS, TimeUnit.MILLISECONDS)
                .observeOn(Schedulers.computation())
                .flatMap(tick -> getCurrentPosition().take(1))
                .subscribe(currentPosition -> {
                    if (!mCurrentPosition.isSubscribedTo()) {
                        stopCurrentPositionClock();
                    } else {
                        mCurrentPosition.invalidate();
                    }
                }, throwable -> {
                    Timber.e(throwable, "Failed to perform position tick");
                });
    }

    private void stopCurrentPositionClock() {
        if (mCurrentPositionClock != null) {
            mCurrentPositionClock.unsubscribe();
            mCurrentPositionClock = null;
        }
    }

    private void initAllProperties() {
        mPlaying.setFunction(mBinding::isPlaying);
        mNowPlaying.setFunction(mBinding::getNowPlaying);
        mQueue.setFunction(mBinding::getQueue);
        mQueuePosition.setFunction(mBinding::getQueuePosition);
        mCurrentPosition.setFunction(mBinding::getCurrentPosition);
        mDuration.setFunction(mBinding::getDuration);
        mMultiRepeatCount.setFunction(mBinding::getMultiRepeatCount);
        mSleepTimerEndTime.setFunction(mBinding::getSleepTimerEndTime);

        invalidateAll();
    }

    private void invalidateAll() {
        mPlaying.invalidate();
        mNowPlaying.invalidate();
        mQueue.invalidate();
        mQueuePosition.invalidate();
        mCurrentPosition.invalidate();
        mDuration.invalidate();
        mMultiRepeatCount.invalidate();
        mSleepTimerEndTime.invalidate();
    }

    @Override
    public Observable<String> getError() {
        return mErrorStream.asObservable();
    }

    @Override
    public Observable<String> getInfo() {
        return mInfoStream.asObservable();
    }

    @Override
    public Observable<PlayerState> getPlayerState() {
        return isPlaying()
                .map(isPlaying -> {
                    PlayerState.Builder builder = new PlayerState.Builder();
                    builder.setPlaying(isPlaying);
                    return builder;
                })
                .concatMap(builder -> getQueue().map(builder::setQueue))
                .concatMap(builder -> getQueuePosition().map(builder::setQueuePosition))
                .concatMap(builder -> getCurrentPosition().map(builder::setSeekPosition))
                .map(PlayerState.Builder::build);
    }

    @Override
    public void restorePlayerState(PlayerState restoreState) {
        editQueue(restoreState.getQueue(), restoreState.getQueuePosition());
        seek(restoreState.getSeekPosition());

        if (restoreState.isPlaying()) play();
    }

    @Override
    public void stop() {
        execute(() -> {
            try {
                mBinding.stop();
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to stop service");
            }
        });
    }

    @Override
    public void skip() {
        execute(() -> {
            try {
                mBinding.skip();
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to skip current track");
            }
        });
    }

    @Override
    public void previous() {
        execute(() -> {
            try {
                mBinding.previous();
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to skip backward");
            }
        });
    }

    @Override
    public void togglePlay() {
        execute(() -> {
            try {
                mBinding.togglePlay();
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to toggle playback");
            }
        });
    }

    @Override
    public void play() {
        execute(() -> {
            try {
                mBinding.play();
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to resume playback");
            }
        });
    }

    @Override
    public void pause() {
        execute(() -> {
            try {
                mBinding.pause();
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to pause playback");
            }
        });
    }

    @Override
    public void updatePlayerPreferences(ReadOnlyPreferenceStore preferenceStore) {
        execute(() -> {
            try {
                mBinding.setPreferences(new ImmutablePreferenceStore(preferenceStore));
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to update remote player preferences");
            }
        });
    }

    @Override
    public void setQueue(List<Song> newQueue, int newPosition) {
        execute(() -> {
            try {
                mBinding.setQueue(newQueue, newPosition);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to set queue");
            }
        });
    }

    @Override
    public void clearQueue() {
        setQueue(Collections.emptyList(), 0);
    }

    @Override
    public void changeSong(int newPosition) {
        execute(() -> {
            try {
                mBinding.changeSong(newPosition);
                mNowPlaying.invalidate();
                mQueuePosition.invalidate();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to change song");
            }
        });
    }

    @Override
    public void editQueue(List<Song> queue, int newPosition) {
        execute(() -> {
            try {
                mBinding.editQueue(queue, newPosition);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public void queueNext(Song song) {
        execute(() -> {
            try {
                mBinding.queueNext(song);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public void queueNext(List<Song> songs) {
        execute(() -> {
            try {
                mBinding.queueNextList(songs);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public void queueLast(Song song) {
        execute(() -> {
            try {
                mBinding.queueLast(song);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public void queueLast(List<Song> songs) {
        execute(() -> {
            try {
                mBinding.queueLastList(songs);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public void seek(int position) {
        execute(() -> {
            try {
                mBinding.seekTo(position);
                invalidateAll();
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to seek");
            }
        });
    }

    @Override
    public Observable<Boolean> isPlaying() {
        return mPlaying.getObservable();
    }

    @Override
    public Observable<Song> getNowPlaying() {
        return mNowPlaying.getObservable();
    }

    @Override
    public Observable<List<Song>> getQueue() {
        return mQueue.getObservable();
    }

    @Override
    public Observable<Integer> getQueuePosition() {
        return mQueuePosition.getObservable();
    }

    @Override
    public Observable<Integer> getCurrentPosition() {
        startCurrentPositionClock();
        return mCurrentPosition.getObservable();
    }

    @Override
    public Observable<Integer> getDuration() {
        return mDuration.getObservable();
    }

    @Override
    public Observable<Boolean> isShuffleEnabled() {
        return mShuffled.asObservable();
    }

    @Override
    public Observable<Integer> getMultiRepeatCount() {
        return mMultiRepeatCount.getObservable();
    }

    @Override
    public void setMultiRepeatCount(int count) {
        execute(() -> {
            try {
                mBinding.setMultiRepeatCount(count);
                mMultiRepeatCount.setValue(count);
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public Observable<Long> getSleepTimerEndTime() {
        return mSleepTimerEndTime.getObservable();
    }

    @Override
    public void setSleepTimerEndTime(long timestampInMillis) {
        execute(() -> {
            try {
                mBinding.setSleepTimerEndTime(timestampInMillis);
                mSleepTimerEndTime.setValue(timestampInMillis);
            } catch (RemoteException exception) {
                Timber.e(exception, "Failed to do work");
            }
        });
    }

    @Override
    public void disableSleepTimer() {
        setSleepTimerEndTime(0L);
    }

    @Override
    public Observable<Bitmap> getArtwork() {
        if (mArtwork == null) {
            mArtwork = BehaviorSubject.create();

            getNowPlaying()
                    .observeOn(Schedulers.io())
                    .map((Song song) -> {
                        if (song == null) {
                            return null;
                        }

                        return Util.fetchFullArt(mContext, song);
                    })
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(mArtwork::onNext, throwable -> {
                        Timber.e(throwable, "Failed to fetch artwork");
                        mArtwork.onNext(null);
                    });
        }

        return mNowPlaying.getSubject()
                .map(Optional::isPresent)
                .switchMap(current -> {
                    if (current) {
                        return mArtwork;
                    } else {
                        return Observable.empty();
                    }
                });
    }

    /**
     * A {@link BroadcastReceiver} class listening for intents with an
     * {@link MusicPlayer#UPDATE_BROADCAST} action. This broadcast must be sent ordered with this
     * receiver being the highest priority so that the UI can access this class for accurate
     * information from the player service
     */
    public static class Listener extends BroadcastReceiver {

        @Inject
        PlayerController mController;

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mController == null) {
                JockeyApplication.getComponent(context).inject(this);
            }

            if (mController instanceof ServicePlayerController) {
                ServicePlayerController playerController = (ServicePlayerController) mController;

                if (intent.getAction().equals(MusicPlayer.UPDATE_BROADCAST)) {
                    playerController.invalidateAll();
                } else if (intent.getAction().equals(MusicPlayer.INFO_BROADCAST)) {
                    String error = intent.getExtras().getString(MusicPlayer.INFO_EXTRA_MESSAGE);
                    playerController.mErrorStream.onNext(error);

                } else if (intent.getAction().equals(MusicPlayer.ERROR_BROADCAST)) {
                    String info = intent.getExtras().getString(MusicPlayer.ERROR_EXTRA_MSG);
                    playerController.mInfoStream.onNext(info);
                }
            }
        }

    }

    private static final class Prop<T> {

        private final String mName;
        private final BehaviorSubject<Optional<T>> mSubject;

        private Retriever<T> mRetriever;

        public Prop(String propertyName) {
            mName = propertyName;
            mSubject = BehaviorSubject.create();
        }

        public void setFunction(Retriever<T> retriever) {
            mRetriever = retriever;
        }

        public void invalidate() {
            mSubject.onNext(Optional.empty());

            if (mRetriever != null) {
                Observable.fromCallable(mRetriever::retrieve)
                        .subscribeOn(Schedulers.newThread())
                        .map(Optional::ofNullable)
                        .subscribe(mSubject::onNext, throwable -> {
                            Timber.e(throwable, "Failed to fetch " + mName + " property.");
                        });
            }
        }

        public boolean isSubscribedTo() {
            return mSubject.hasObservers();
        }

        public void setValue(T value) {
            mSubject.onNext(Optional.ofNullable(value));
        }

        protected BehaviorSubject<Optional<T>> getSubject() {
            return mSubject;
        }

        public Observable<T> getObservable() {
            return mSubject.asObservable()
                    .subscribeOn(AndroidSchedulers.mainThread())
                    .filter(Optional::isPresent)
                    .map(Optional::getValue)
                    .distinctUntilChanged()
                    .observeOn(AndroidSchedulers.mainThread());
        }

        interface Retriever<T> {
            T retrieve() throws Exception;
        }

    }
}
