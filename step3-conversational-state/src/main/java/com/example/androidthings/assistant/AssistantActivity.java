/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidthings.assistant;

import android.app.Activity;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import com.example.androidthings.assistant.shared.BoardDefaults;
import com.example.androidthings.assistant.shared.Credentials;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.contrib.driver.voicehat.Max98357A;
import com.google.android.things.contrib.driver.voicehat.VoiceHat;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.assistant.embedded.v1alpha1.AudioInConfig;
import com.google.assistant.embedded.v1alpha1.AudioOutConfig;
import com.google.assistant.embedded.v1alpha1.ConverseConfig;
import com.google.assistant.embedded.v1alpha1.ConverseRequest;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.assistant.embedded.v1alpha1.ConverseState;
import com.google.assistant.embedded.v1alpha1.EmbeddedAssistantGrpc;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.json.JSONException;

public class AssistantActivity extends Activity implements Button.OnButtonEventListener {
    private static final String TAG = AssistantActivity.class.getSimpleName();

    // Peripheral and drivers constants.
    private static final boolean USE_VOICEHAT_DAC = true;
    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;

    // Audio constants.
    private static final int SAMPLE_RATE = 16000;
    private static final int DEFAULT_VOLUME = 100;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;

    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .build();
    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .build();
    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(ENCODING)
            .setSampleRate(SAMPLE_RATE)
            .build();
    private static final int SAMPLE_BLOCK_SIZE = 1024;
    private int mOutputBufferSize;

    // Google Assistant API constants.
    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";

    // gRPC client and stream observers.
    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<ConverseRequest> mAssistantRequestObserver;
    private StreamObserver<ConverseResponse> mAssistantResponseObserver =
            new StreamObserver<ConverseResponse>() {
        @Override
        public void onNext(ConverseResponse value) {
            switch (value.getConverseResponseCase()) {
                case EVENT_TYPE:
                    Log.d(TAG, "converse response event: " + value.getEventType());
                    break;
                case RESULT:
                    final String spokenRequestText = value.getResult().getSpokenRequestText();
                    if (value.getResult().getVolumePercentage() != 0) {
                        mVolumePercentage = value.getResult().getVolumePercentage();
                        Log.i(TAG, "assistant volume changed: " + mVolumePercentage);
                        mAudioTrack.setVolume(AudioTrack.getMaxVolume() *
                            mVolumePercentage / 100.0f);
                    }
                    mConversationState = value.getResult().getConversationState();
                    if (!spokenRequestText.isEmpty()) {
                        Log.i(TAG, "assistant request text: " + spokenRequestText);
                        mMainHandler.post(() -> mAssistantRequestsAdapter.add(spokenRequestText));
                    }
                    break;
                case AUDIO_OUT:
                    final ByteBuffer audioData =
                            ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());
                    Log.d(TAG, "converse audio size: " + audioData.remaining());
                    mAssistantResponses.add(audioData);
                    if (mLed != null) {
                        try {
                            mLed.setValue(!mLed.getValue());
                        } catch (IOException e) {
                            Log.w(TAG, "error toggling LED:", e);
                        }
                    }
                    break;
                case ERROR:
                    Log.e(TAG, "converse response error: " + value.getError());
                    break;
            }
        }

        @Override
        public void onError(Throwable t) {
            Log.e(TAG, "converse error:", t);
        }

        @Override
        public void onCompleted() {
            mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(mOutputBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
            if (mAudioOutputDevice != null) {
                mAudioTrack.setPreferredDevice(mAudioOutputDevice);
            }
            mAudioTrack.play();
            if (mDac != null) {
                try {
                    mDac.setSdMode(Max98357A.SD_MODE_LEFT);
                } catch (IOException e) {
                    Log.e(TAG, "unable to modify dac trigger", e);
                }
            }
            for (ByteBuffer audioData : mAssistantResponses) {
                final ByteBuffer buf = audioData;
                Log.d(TAG, "Playing a bit of audio");
                mAudioTrack.write(buf, buf.remaining(),
                    AudioTrack.WRITE_BLOCKING);
            }
            mAssistantResponses.clear();
            mAudioTrack.stop();
            if (mDac != null) {
                try {
                    mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);
                } catch (IOException e) {
                    Log.e(TAG, "unable to modify gpio peripherals", e);
                }
            }
            Log.i(TAG, "assistant response finished");
            if (mLed != null) {
                try {
                    mLed.setValue(false);
                } catch (IOException e) {
                    Log.e(TAG, "error turning off LED:", e);
                }
            }
        }
    };

    // Audio playback and recording objects.
    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;
    private int mVolumePercentage = DEFAULT_VOLUME;

    // Audio routing configuration: use default routing.
    private AudioDeviceInfo mAudioInputDevice;
    private AudioDeviceInfo mAudioOutputDevice;

    // Hardware peripherals.
    private Button mButton;
    private Gpio mLed;
    private Max98357A mDac;

    // Assistant Thread and Runnables implementing the push-to-talk functionality.
    private ByteString mConversationState = null;
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;
    private ArrayList<ByteBuffer> mAssistantResponses = new ArrayList<>();
    private Runnable mStartAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "starting assistant request");
            mAudioRecord.startRecording();
            mAssistantRequestObserver = mAssistantService.converse(mAssistantResponseObserver);
            ConverseConfig.Builder converseConfigBuilder = ConverseConfig.newBuilder()
                    .setAudioInConfig(AudioInConfig.newBuilder()
                            .setEncoding(ENCODING_INPUT)
                            .setSampleRateHertz(SAMPLE_RATE)
                            .build())
                    .setAudioOutConfig(AudioOutConfig.newBuilder()
                            .setEncoding(ENCODING_OUTPUT)
                            .setSampleRateHertz(SAMPLE_RATE)
                            .setVolumePercentage(mVolumePercentage)
                            .build());
            if (mConversationState != null) {
                converseConfigBuilder.setConverseState(ConverseState.newBuilder()
                        .setConversationState(mConversationState)
                        .build());
            }
            mAssistantRequestObserver.onNext(
                ConverseRequest.newBuilder()
                    .setConfig(converseConfigBuilder.build())
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStreamAssistantRequest = new Runnable() {
        @Override
        public void run() {
            ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            if (mAudioInputDevice != null) {
                mAudioRecord.setPreferredDevice(mAudioInputDevice);
            }
            int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result < 0) {
                Log.e(TAG, "error reading from audio stream:" + result);
                return;
            }
            Log.d(TAG, "streaming ConverseRequest: " + result);
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStopAssistantRequest = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "ending assistant request");
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            mAudioRecord.stop();
            mAudioTrack.play();
        }
    };
    private Handler mMainHandler;

    // List & adapter to store and display the history of Assistant Requests.
    private ArrayList<String> mAssistantRequests = new ArrayList<>();
    private ArrayAdapter<String> mAssistantRequestsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "starting assistant demo");

        setContentView(R.layout.activity_main);
        ListView assistantRequestsListView = findViewById(R.id.assistantRequestsListView);
        mAssistantRequestsAdapter =
            new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                mAssistantRequests);
        assistantRequestsListView.setAdapter(mAssistantRequestsAdapter);
        mMainHandler = new Handler(getMainLooper());

        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());

        // Use I2S with the Voice HAT.
        if (USE_VOICEHAT_DAC) {
            Log.d(TAG, "enumerating devices");
            mAudioInputDevice = findAudioDevice(AudioManager.GET_DEVICES_INPUTS,
                    AudioDeviceInfo.TYPE_BUS);
            if (mAudioInputDevice == null) {
                Log.e(TAG, "failed to found preferred audio input device, using default");
            }
            mAudioOutputDevice = findAudioDevice(AudioManager.GET_DEVICES_OUTPUTS,
                    AudioDeviceInfo.TYPE_BUS);
            if (mAudioOutputDevice == null) {
                Log.e(TAG, "failed to found preferred audio output device, using default");
            }
        }

        try {
            if (USE_VOICEHAT_DAC) {
                Log.i(TAG, "initializing DAC trigger");
                mDac = VoiceHat.openDac();
                mDac.setSdMode(Max98357A.SD_MODE_SHUTDOWN);

                mButton = VoiceHat.openButton();
                mLed = VoiceHat.openLed();
            } else {
                PeripheralManagerService pioService = new PeripheralManagerService();
                mButton = new Button(BoardDefaults.getGPIOForButton(),
                    Button.LogicState.PRESSED_WHEN_LOW);
                mLed = pioService.openGpio(BoardDefaults.getGPIOForLED());
            }

            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);

            mLed.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
            mLed.setActiveType(Gpio.ACTIVE_HIGH);
        } catch (IOException e) {
            Log.e(TAG, "error configuring peripherals:", e);
            return;
        }

        AudioManager manager = (AudioManager)this.getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        Log.i(TAG, "setting volume to: " + maxVolume);
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);
        mOutputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());
        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(mOutputBufferSize)
                .build();
        mAudioTrack.play();
        int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_STEREO.getSampleRate(),
                AUDIO_FORMAT_STEREO.getChannelMask(),
                AUDIO_FORMAT_STEREO.getEncoding());
        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(inputBufferSize)
                .build();

        ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(
                            Credentials.fromResource(this, R.raw.credentials)
                    ));
        } catch (IOException|JSONException e) {
            Log.e(TAG, "error creating assistant service:", e);
        }
    }

    private AudioDeviceInfo findAudioDevice(int deviceFlag, int deviceType) {
        AudioManager manager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] adis = manager.getDevices(deviceFlag);
        for (AudioDeviceInfo adi : adis) {
            if (adi.getType() == deviceType) {
                return adi;
            }
        }
        return null;
    }

    @Override
    public void onButtonEvent(Button button, boolean pressed) {
        try {
            if (mLed != null) {
                mLed.setValue(pressed);
            }
        } catch (IOException e) {
            Log.d(TAG, "error toggling LED:", e);
        }
        if (pressed) {
            mAssistantHandler.post(mStartAssistantRequest);
        } else {
            mAssistantHandler.post(mStopAssistantRequest);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "destroying assistant demo");
        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
        if (mLed != null) {
            try {
                mLed.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing LED", e);
            }
            mLed = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing button", e);
            }
            mButton = null;
        }
        if (mDac != null) {
            try {
                mDac.close();
            } catch (IOException e) {
                Log.w(TAG, "error closing voice hat trigger", e);
            }
            mDac = null;
        }
        mAssistantHandler.post(() -> mAssistantHandler.removeCallbacks(mStreamAssistantRequest));
        mAssistantThread.quitSafely();
    }
}
