package com.bajajnav.relay;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

import java.util.Arrays;

/**
 * Decodes the control characteristic: what the rider pressed on the cluster.
 *
 * This cluster advertises READ only (props 0x02, no CCCD), so there is no notify
 * to subscribe to — {@link ClusterLink} polls the value every 700 ms exactly like
 * the official app, and we act on bytes that changed since the last sample.
 *
 * Byte map, from com.bajajconnect.ble.Controls.processData:
 * <pre>
 *   0  bits1-4 volume, bits6-7 dial source
 *   1  call accept        2  call reject        3  call reject with SMS
 *   4  bits1-6 playlist page, bits7-8 new playlist request
 *   5  take me home       6  resume            7  pause
 *   8  next track         9  previous track   10  stop
 *  11  missed call get   12  alert get        13  launch media player
 *  14  playlist song req 15  playlist song    16  dial index   17  dial txn
 * </pre>
 *
 * The first sample only seeds the "previous" values: the cluster keeps sticky
 * values in there and we must not treat them as presses.
 */
final class Controls {
    private static byte[] last;
    private static int volumeNibble = -1;
    private static int callAccept;
    private static int callReject;
    private static int callRejectSms;
    private static int missedGet;
    private static int alertGet;
    private static int resume;
    private static int pause;
    private static int next;
    private static int prev;
    private static int stop;
    private static boolean bootstrapped;

    private Controls() {
    }

    static synchronized void process(byte[] b) {
        if (b == null || b.length < 18) {
            return;
        }
        if (last != null && Arrays.equals(last, b)) {
            return;
        }
        last = Arrays.copyOf(b, b.length);

        int vol = (b[0] >> 1) & 0x0F;
        int accept = b[1] & 0xFF;
        int reject = b[2] & 0xFF;
        int rejectSms = b[3] & 0xFF;
        int resumeKey = b[6] & 0xFF;
        int pauseKey = b[7] & 0xFF;
        int nextKey = b[8] & 0xFF;
        int prevKey = b[9] & 0xFF;
        int stopKey = b[10] & 0xFF;
        int missed = b[11] & 0xFF;
        int alert = b[12] & 0xFF;

        if (!bootstrapped) {
            bootstrapped = true;
            volumeNibble = vol;
            callAccept = accept;
            callReject = reject;
            callRejectSms = rejectSms;
            resume = resumeKey;
            pause = pauseKey;
            next = nextKey;
            prev = prevKey;
            stop = stopKey;
            missedGet = missed;
            alertGet = alert;
            Lg.i("control bootstrap vol=" + vol + " val=" + TbtFrame.hex(b));
            return;
        }

        if (vol != volumeNibble) {
            volumeNibble = vol;
            setVolume(vol);
        }
        if (accept != callAccept) {
            callAccept = accept;
            PhoneBridge.get().onAcceptRequested(accept);
        }
        if (reject != callReject) {
            callReject = reject;
            PhoneBridge.get().onRejectRequested(reject);
        }
        if (rejectSms != callRejectSms) {
            callRejectSms = rejectSms;
            PhoneBridge.get().onRejectWithSmsRequested(rejectSms);
        }
        if (missed != missedGet) {
            missedGet = missed;
            PhoneBridge.get().onMissedCallRequested(missed);
        }
        if (alert != alertGet) {
            alertGet = alert;
            PhoneBridge.get().onAlertRequested(alert);
        }
        if (resumeKey != resume) {
            resume = resumeKey;
            media(KeyEvent.KEYCODE_MEDIA_PLAY);
        }
        if (pauseKey != pause) {
            pause = pauseKey;
            media(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
        if (nextKey != next) {
            next = nextKey;
            media(KeyEvent.KEYCODE_MEDIA_NEXT);
        }
        if (prevKey != prev) {
            prev = prevKey;
            media(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
        if (stopKey != stop) {
            stop = stopKey;
            media(KeyEvent.KEYCODE_MEDIA_STOP);
        }
    }

    private static void setVolume(int nibble) {
        Context c = RelayService.context();
        if (c == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int index = Math.round(nibble * max / 15f);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
            GeneralPacket.volume = nibble;
            Lg.i("cluster volume " + nibble + "/15 -> index " + index);
            RelayService.pushGeneralNow();
        } catch (Exception e) {
            Lg.w("set volume failed: " + e);
        }
    }

    /** Media buttons, so the cluster's play/pause/skip keys keep working. */
    private static void media(int keyCode) {
        Context c = RelayService.context();
        if (c == null) {
            return;
        }
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            Lg.i("cluster media key " + keyCode);
        } catch (Exception e) {
            Lg.w("media key failed: " + e);
        }
    }
}
