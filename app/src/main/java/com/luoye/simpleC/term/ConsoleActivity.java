package com.luoye.simpleC.term;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import com.luoye.simpleC.R;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.IOException;
import java.util.List;

/**
 * Created by zyw on 2017/11/12.
 */
public class ConsoleActivity extends Activity implements ServiceConnection{

    private Process process = null;
    private static final String TAG = "ConsoleActivity";
    public TerminalView mEmulatorView;
    private TerminalSession mSession;
    private final  int MSG_SESSION_FINISH=0x100;
    private  MediaPlayer mediaPlayer=null;
    public TermuxService mTermService;
    private String cmd;
    private int mFontSize;
    private static int MIN_FONTSIZE;
    private static final int MAX_FONTSIZE = 256;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.console);
        computeFontSize();
        initView();
        startService();
    }

    private void computeFontSize(){
        //计算字体大小
        float dipInPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, this.getResources().getDisplayMetrics());
        MIN_FONTSIZE = (int) (4f * dipInPixels);
        int defaultFontSize = Math.round(12 * dipInPixels);
        // Make it divisible by 2 since that is the minimal adjustment step:
        if (defaultFontSize % 2 == 1) defaultFontSize--;

        mFontSize = defaultFontSize;
        mFontSize = Math.max(MIN_FONTSIZE, Math.min(mFontSize, MAX_FONTSIZE));

    }
    private  void initView(){
        ActionBar actionBar=getActionBar();
        if(actionBar!=null)
            actionBar.setDisplayHomeAsUpEnabled(true);
        mEmulatorView=(TerminalView) findViewById(R.id.emulatorView) ;
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        cmd=getIntent().getStringExtra("bin");
        mEmulatorView.setTextSize(mFontSize);
        mEmulatorView.requestFocus();
        mEmulatorView.setOnKeyListener(new TermuxViewClient(this));

        AssetManager am = getAssets();
        AssetFileDescriptor assetFileDescriptor = null;

        try {
            assetFileDescriptor = am.openFd("bell.mp3");

            mediaPlayer=new MediaPlayer();
            mediaPlayer.setDataSource(assetFileDescriptor.getFileDescriptor());

            mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    mp.release();
                }
            });
            mediaPlayer.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private void  startService(){
        Intent serviceIntent = new Intent(this, TermuxService.class);
        // Start the service and make it run regardless of who is bound to it:
        serviceIntent.setAction(TermuxService.ACTION_EXECUTE);
        String uriStr =  "file:///"+cmd;
        serviceIntent.setData(Uri.parse(uriStr));
        startService(serviceIntent);
        if (!bindService(serviceIntent, this, 0))
            throw new RuntimeException("bindService() failed");
    }

    @Override
    protected void onStart() {
        super.onStart();
        mEmulatorView.onScreenUpdated();
    }

    /**
     * Intercepts keys before the view/terminal gets it.
     */
    private View.OnKeyListener mKeyListener = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            return backkeyInterceptor(keyCode, event) || keyboardShortcuts(keyCode, event);
        }

        /**
         * Keyboard shortcuts (tab management, paste)
         */
        private boolean keyboardShortcuts(int keyCode, KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            boolean isCtrlPressed = (event.getMetaState() & KeyEvent.META_CTRL_ON) != 0;
            boolean isShiftPressed = (event.getMetaState() & KeyEvent.META_SHIFT_ON) != 0;

            if (keyCode == KeyEvent.KEYCODE_TAB && isCtrlPressed) {
                if (isShiftPressed) {
                    //mViewFlipper.showPrevious();
                } else {
                    //mViewFlipper.showNext();
                }

                return true;
            } else if (keyCode == KeyEvent.KEYCODE_V && isCtrlPressed && isShiftPressed) {
                doPaste();

                return true;
            } else {
                return false;
            }
        }

        private boolean backkeyInterceptor(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK ) {
                //onKeyUp(keyCode, event);

                return false;
            } else {
                return false;
            }
        }
    };
    void doPaste() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null) return;
        CharSequence paste = clipData.getItemAt(0).coerceToText(this);
        if (!TextUtils.isEmpty(paste))
            getCurrentTermSession().getEmulator().paste(paste.toString());
    }

    private TerminalSession getCurrentTermSession() {
        return mTermService.getTermSession();
    }

    /**
     *
     * Send a URL up to Android to be handled by a browser.
     * @param link The URL to be opened.
     */
    private void execURL(String link)
    {
        Uri webLink = Uri.parse(link);
        Intent openLink = new Intent(Intent.ACTION_VIEW, webLink);
        PackageManager pm = getPackageManager();
        List<ResolveInfo> handlers = pm.queryIntentActivities(openLink, 0);
        if(handlers.size() > 0)
            startActivity(openLink);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(this);
        mTermService.stopSelf();
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId()==android.R.id.home)
        {
            finish();
        }
        return super.onOptionsItemSelected(item);
    }

    private long startTime;
    private long endTime;
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        mTermService = ((TermuxService.LocalBinder) service).service;
        mEmulatorView.attachSession(mTermService.getTermSession());
        startTime=System.currentTimeMillis();
        mTermService.mSessionChangeCallback = new TerminalSession.SessionChangedCallback() {
            @Override
            public void onTextChanged(TerminalSession changedSession) {
                mEmulatorView.onScreenUpdated();
            }

            @Override
            public void onTitleChanged(TerminalSession updatedSession) {

            }

            @Override
            public void onSessionFinished(final TerminalSession finishedSession) {
               // if (mTermService.mWantsToStop) {
                    // The service wants to stop as soon as possible.
                    endTime=System.currentTimeMillis();
                    showToast("程序结束，耗时："+(endTime-startTime)/1000.0+"s");
            }

            @Override
            public void onClipboardText(TerminalSession session, String text) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(new ClipData(null, new String[]{"text/plain"}, new ClipData.Item(text)));
            }

            @Override
            public void onBell(TerminalSession session) {
                if(mediaPlayer!=null){
                    mediaPlayer.start();
                }


            }

            @Override
            public void onColorsChanged(TerminalSession changedSession) {

            }
        };
    };

    @Override
    public void onServiceDisconnected(ComponentName componentName) {

    }

    public void changeFontSize(boolean increase) {
        mFontSize += (increase ? 1 : -1) * 2;
        mFontSize = Math.max(MIN_FONTSIZE, Math.min(mFontSize, MAX_FONTSIZE));
        mEmulatorView.setTextSize(mFontSize);
    }

    private Toast toast;
    private void showToast(CharSequence text)
    {
        if(toast==null)
        {
            toast=Toast.makeText(this,text,Toast.LENGTH_LONG);
        }
        else
        {
            toast.setText(text);
        }
        toast.show();
    }
}
