/*
 * Copyright 2017 Gabor Varadi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zhuinden.xkcdexample;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.zhuinden.statebundle.StateBundle;
import com.zhuinden.xkcdexample.redux.Action;
import com.zhuinden.xkcdexample.redux.ReduxStore;
import com.zhuinden.xkcdexample.redux.State;

import java.util.Random;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.OnLongClick;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.realm.Realm;
import io.realm.RealmChangeListener;
import io.realm.RealmResults;

import static com.zhuinden.xkcdexample.XkcdActions.COMIC_CHANGED;
import static com.zhuinden.xkcdexample.XkcdActions.COMIC_SAVED;
import static com.zhuinden.xkcdexample.XkcdActions.DOWNLOAD_CURRENT;
import static com.zhuinden.xkcdexample.XkcdActions.GO_TO_LATEST;
import static com.zhuinden.xkcdexample.XkcdActions.INITIALIZE;
import static com.zhuinden.xkcdexample.XkcdActions.JUMP_TO_NUMBER;
import static com.zhuinden.xkcdexample.XkcdActions.NETWORK_ERROR;
import static com.zhuinden.xkcdexample.XkcdActions.NEXT_COMIC;
import static com.zhuinden.xkcdexample.XkcdActions.OPEN_IN_BROWSER;
import static com.zhuinden.xkcdexample.XkcdActions.OPEN_JUMP_DIALOG;
import static com.zhuinden.xkcdexample.XkcdActions.OPEN_LINK;
import static com.zhuinden.xkcdexample.XkcdActions.PREVIOUS_COMIC;
import static com.zhuinden.xkcdexample.XkcdActions.RANDOM_COMIC;
import static com.zhuinden.xkcdexample.XkcdActions.RETRY_DOWNLOAD;
import static com.zhuinden.xkcdexample.XkcdActions.SHOW_ALT_TEXT;

public class MainActivity
        extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    @BindView(R.id.xkcd_image)
    ImageView image;

    @OnClick(R.id.xkcd_previous)
    public void previous() {
        reduxStore.dispatch(Action.create(PREVIOUS_COMIC));
    }

    @OnClick(R.id.xkcd_next)
    public void next() {
        reduxStore.dispatch(Action.create(NEXT_COMIC));
    }

    @OnClick(R.id.xkcd_random)
    public void random() {
        reduxStore.dispatch(Action.create(RANDOM_COMIC));
    }

    private void openOrDownloadByNumber(int number) {
        StateBundle stateBundle = new StateBundle();
        XkcdState.putNumber(stateBundle, number);
        reduxStore.dispatch(Action.create(JUMP_TO_NUMBER, stateBundle));
    }

    private boolean queryAndShowComicIfExists(int number) {
        XkcdComic xkcdComic = getXkcdComic(number);
        if(xkcdComic != null) {
            storeAndOpenComic(xkcdComic);
            return true;
        }
        return false;
    }

    private XkcdComic getXkcdComic(int number) {
        return realm.where(XkcdComic.class).equalTo(XkcdComicFields.NUM, number).findFirst();
    }

    private void storeAndOpenComic(XkcdComic xkcdComic) {
        this.xkcdComic = xkcdComic;
        updateUi(xkcdComic);
    }

    private void updateUi(XkcdComic xkcdComic) {
        getSupportActionBar().setTitle("#" + xkcdComic.getNum() + ": " + xkcdComic.getTitle());
        Glide.with(this).load(xkcdComic.getImg()).diskCacheStrategy(DiskCacheStrategy.ALL).into(image);
    }

    @OnLongClick(R.id.xkcd_image)
    public boolean longClickImage() {
        if(xkcdComic != null) {
            StateBundle stateBundle = new StateBundle();
            XkcdState.putAltText(stateBundle, xkcdComic.getAlt());
            reduxStore.dispatch(Action.create(SHOW_ALT_TEXT, stateBundle));
            return true;
        }
        return false;
    }

    private void showAltText(String altText) {
        Toast.makeText(this, altText, Toast.LENGTH_LONG).show();
    }

    @OnClick(R.id.xkcd_image)
    public void clickImage() {
        if(xkcdComic != null) {
            StateBundle stateBundle = new StateBundle();
            XkcdState.putLink(stateBundle, xkcdComic.getLink());
            reduxStore.dispatch(Action.create(OPEN_LINK, stateBundle));
        }
    }

    XkcdMapper xkcdMapper;

    XkcdService xkcdService;

    ReduxStore reduxStore;

    Random random;

    Realm realm;

    RealmResults<XkcdComic> results;

    XkcdComic xkcdComic;

    RealmChangeListener<RealmResults<XkcdComic>> realmChangeListener = element -> {
        reduxStore.dispatch(Action.create(COMIC_CHANGED));
    };

    AlertDialog jumpDialog;

    private void openJumpDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_jump, null, false);
        final EditText jumpNumbers = ButterKnife.findById(dialogView, R.id.jump_numbers);
        jumpDialog = new AlertDialog.Builder(this) //
                .setTitle(R.string.jump_to) //
                .setView(dialogView) //
                .setPositiveButton(R.string.jump, (dialog, which) -> {
                    startJumpToNumber(jumpNumbers);
                    hideKeyboard(jumpNumbers);
                }).setNegativeButton(R.string.cancel, (dialog, which) -> {
                    hideKeyboard(jumpNumbers);
                }).setOnCancelListener(dialog -> {
                    hideKeyboard(jumpNumbers);
                }).create();
        jumpNumbers.setOnEditorActionListener((v, actionId, event) -> {
            if((event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) || actionId == EditorInfo.IME_ACTION_DONE) {
                startJumpToNumber(jumpNumbers);
                hideKeyboard(jumpNumbers);
                cancelJumpDialogIfShowing();
                return true;
            }
            return false;
        });
        jumpDialog.show();
        showKeyboard(jumpNumbers);
    }

    private void startJumpToNumber(EditText jumpNumbers) {
        String _number = jumpNumbers.getText().toString();
        if(!"".equals(_number)) {
            int number = Integer.parseInt(_number);
            openOrDownloadByNumber(number);
        }
    }

    private void cancelJumpDialogIfShowing() {
        if(jumpDialog != null && jumpDialog.isShowing()) {
            jumpDialog.cancel();
            jumpDialog = null;
        }
    }

    private void showKeyboard(View view) {
        view.postDelayed(() -> {
            view.setFocusableInTouchMode(true);
            view.requestFocus();
            ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, 0);
        }, 300);
    }

    private void hideKeyboard(View view) {
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(view.getWindowToken(),
                0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN); // always is needed here
    }

    Disposable disposable;

    @Override
    @SuppressWarnings("NewApi")
    protected void onCreate(Bundle savedInstanceState) {
        reduxStore = CustomApplication.get(this).reduxStore();
        if(savedInstanceState != null) {
            if(reduxStore.isAtInitialState()) {
                reduxStore.setInitialState(savedInstanceState.getParcelable("state"));
            }
        }
        super.onCreate(savedInstanceState);
        MainScopeListener mainScopeListener = (MainScopeListener) getSupportFragmentManager().findFragmentByTag(
                "SCOPE_LISTENER");
        if(mainScopeListener == null) {
            mainScopeListener = new MainScopeListener();
            getSupportFragmentManager().beginTransaction().add(mainScopeListener, "SCOPE_LISTENER").commitNow();
        }
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        realm = Realm.getDefaultInstance();
        results = realm.where(XkcdComic.class).findAll();
        results.addChangeListener(realmChangeListener);

        this.disposable = reduxStore.state().observeOn(AndroidSchedulers.mainThread()).subscribe(stateChange -> {
            Log.i(TAG, "[" + stateChange + "]");
            State state = stateChange.newState();
            int current = XkcdState.current(state.state());

            switch(state.action().type()) {
                case SHOW_ALT_TEXT:
                    String altText = XkcdState.altText(state.action().payload());
                    showAltText(altText);
                    break;
                case PREVIOUS_COMIC:
                case NEXT_COMIC:
                case RANDOM_COMIC:
                case JUMP_TO_NUMBER:
                case GO_TO_LATEST:
                    if(!queryAndShowComicIfExists(current)) {
                        reduxStore.dispatch(Action.create(DOWNLOAD_CURRENT));
                    }
                    break;
                case COMIC_CHANGED:
                case COMIC_SAVED:
                case INITIALIZE:
                    queryAndShowComicIfExists(current);
                    break;
                case NETWORK_ERROR:
                    showNetworkError();
                    break;
                case OPEN_LINK:
                    String link = XkcdState.link(state.action().payload());
                    if(link != null && !"".equals(link)) {
                        openUriWithBrowser(Uri.parse(link));
                    }
                    break;
                case OPEN_IN_BROWSER:
                    openUriWithBrowser(Uri.parse("https://xkcd.com/" + current));
                    break;
                case OPEN_JUMP_DIALOG:
                    openJumpDialog();
                    break;
            }
        });

        xkcdService = CustomApplication.get(this).xkcdService();
        xkcdMapper = CustomApplication.get(this).xkcdMapper();
        random = CustomApplication.get(this).random();

        reduxStore.dispatch(Action.create(INITIALIZE));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable("state", reduxStore.getState());
    }

    @Override
    protected void onDestroy() {
        disposable.dispose();
        disposable = null;
        results.removeChangeListener(realmChangeListener);
        realm.close();
        realm = null;
        cancelJumpDialogIfShowing();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.action_latest:
                reduxStore.dispatch(Action.create(GO_TO_LATEST));
                return true;
            case R.id.action_jump:
                reduxStore.dispatch(Action.create(OPEN_JUMP_DIALOG));
                return true;
            case R.id.action_retry:
                reduxStore.dispatch(Action.create(RETRY_DOWNLOAD));
                return true;
            case R.id.action_open_browser:
                reduxStore.dispatch(Action.create(OPEN_IN_BROWSER));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openUriWithBrowser(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        startActivity(intent);
    }

    public static ReduxStore getStore(Context context) {
        // noinspection ResourceType
        return (ReduxStore) context.getSystemService("REDUX_STORE");
    }

    private void showNetworkError() {
        Toast.makeText(MainActivity.this, R.string.please_retry_with_active_internet, Toast.LENGTH_SHORT).show();
    }

    @Override
    public Object getSystemService(@NonNull String name) {
        if("REDUX_STORE".equals(name)) {
            return reduxStore;
        }
        return super.getSystemService(name);
    }
}