/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramFonts;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/** The app's font: the bundled one, the device font, one of a curated set (downloaded once), or the user's own
 *  font from a file or a link. One choice restyles the whole app (see {@link SolegramFonts}). */
public class SolegramFontsActivity extends BaseFragment {

    private static final int REQUEST_FONT_FILE = 9741;

    /** family, a github raw path to its full (Latin + Cyrillic) variable font, and the name shown. */
    private static final String[][] CURATED = {
        {"Inter", "ofl/inter/Inter%5Bopsz,wght%5D.ttf"},
        {"Manrope", "ofl/manrope/Manrope%5Bwght%5D.ttf"},
        {"Onest", "ofl/onest/Onest%5Bwght%5D.ttf"},
        {"Golos Text", "ofl/golostext/GolosText%5Bwght%5D.ttf"},
        {"Nunito", "ofl/nunito/Nunito%5Bwght%5D.ttf"},
        {"Rubik", "ofl/rubik/Rubik%5Bwght%5D.ttf"},
        {"Montserrat", "ofl/montserrat/Montserrat%5Bwght%5D.ttf"},
        {"Jost", "ofl/jost/Jost%5Bwght%5D.ttf"},
        {"Lora", "ofl/lora/Lora%5Bwght%5D.ttf"},
        {"Comfortaa", "ofl/comfortaa/Comfortaa%5Bwght%5D.ttf"},
    };
    private static final String GITHUB = "https://raw.githubusercontent.com/google/fonts/main/";

    private RecyclerListView listView;
    private ListAdapter adapter;
    private FontPreview preview;

    private int rowCount;
    private int previewRow;
    private int defaultRow;
    private int systemRow;
    private int fontsHeaderRow;
    private int firstFontRow;
    private int lastFontRow;
    private int fileRow;
    private int linkRow;
    private int infoRow;

    private boolean working;

    private void build() {
        rowCount = 0;
        previewRow = rowCount++;
        defaultRow = rowCount++;
        systemRow = rowCount++;
        fontsHeaderRow = rowCount++;
        firstFontRow = rowCount;
        rowCount += CURATED.length;
        lastFontRow = rowCount - 1;
        fileRow = rowCount++;
        linkRow = rowCount++;
        infoRow = rowCount++;
    }

    @Override
    public boolean onFragmentCreate() {
        build();
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_Font));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (working) {
                return;
            }
            if (position == defaultRow) {
                apply("", "", null);
            } else if (position == systemRow) {
                apply("system", "", null);
            } else if (position >= firstFontRow && position <= lastFontRow) {
                final String[] font = CURATED[position - firstFontRow];
                downloadCurated(font[0], GITHUB + font[1]);
            } else if (position == fileRow) {
                pickFile();
            } else if (position == linkRow) {
                askLink();
            }
        });

        return fragmentView;
    }

    private void apply(String mode, String name, Runnable then) {
        SolegramConfig.setAppFont(mode, name);
        AndroidUtilities.runOnUIThread(() -> {
            if (preview != null) {
                preview.refresh();
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (then != null) {
                then.run();
            }
            BulletinFactory.global().createSimpleBulletin(R.raw.contact_check, getString(R.string.SG_FontApplied)).show();
        });
    }

    // ------------------------------------------------------------------ download / install

    private void downloadCurated(String name, String url) {
        setWorking(true);
        Utilities.globalQueue.postRunnable(() -> {
            final File tmp = new File(SolegramFonts.dir(), "download.tmp");
            final boolean ok = download(url, tmp);
            AndroidUtilities.runOnUIThread(() -> {
                if (ok) {
                    installBase(tmp, name);
                } else {
                    failed();
                }
            });
        });
    }

    private void installFromLink(String url) {
        setWorking(true);
        Utilities.globalQueue.postRunnable(() -> {
            final File tmp = new File(SolegramFonts.dir(), "download.tmp");
            final boolean ok = download(url, tmp);
            AndroidUtilities.runOnUIThread(() -> {
                if (ok) {
                    installBase(tmp, getString(R.string.SG_FontCustom));
                } else {
                    failed();
                }
            });
        });
    }

    /** Moves a validated font into place as the single base file, drops the rest, and applies it. */
    private void installBase(File tmp, String name) {
        if (!SolegramFonts.looksLikeFont(tmp)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            failed();
            return;
        }
        // clear any previous font files, then keep this one as the 400 base (other weights are synthesised)
        final File dir = SolegramFonts.dir();
        final File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!f.equals(tmp)) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        final File base = new File(dir, SolegramFonts.fileName(400, false));
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(base);
        setWorking(false);
        apply("custom", name, null);
    }

    private void failed() {
        setWorking(false);
        BulletinFactory.global().createErrorBulletin(getString(R.string.SG_FontFailed)).show();
    }

    private static boolean download(String url, File out) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            // a plain non-browser agent, so Google Fonts and others serve a ttf, not woff2
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) {
                return false;
            }
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                final byte[] buf = new byte[16384];
                int r;
                long total = 0;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    total += r;
                    if (total > 8 * 1024 * 1024) { // a font is not this big: refuse
                        return false;
                    }
                }
            }
            return out.length() > 256;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    private void setWorking(boolean w) {
        AndroidUtilities.runOnUIThread(() -> {
            working = w;
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void askLink() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        final EditTextBoldCursor edit = new EditTextBoldCursor(activity);
        edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        edit.setHintText(getString(R.string.SG_FontLinkHint));
        edit.setHintColor(getThemedColor(Theme.key_dialogTextHint));
        edit.setSingleLine(true);
        edit.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), 0);
        final FrameLayout wrap = new FrameLayout(activity);
        wrap.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 22, 4, 22, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setTitle(getString(R.string.SG_FontFromLink));
        b.setView(wrap);
        b.setPositiveButton(getString(R.string.Done), (dialog, which) -> {
            final String url = edit.getText().toString().trim();
            if (url.startsWith("http://") || url.startsWith("https://")) {
                installFromLink(url);
            } else {
                BulletinFactory.global().createErrorBulletin(getString(R.string.SG_FontLinkBad)).show();
            }
        });
        b.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(b.create());
    }

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream"});
            startActivityForResult(intent, REQUEST_FONT_FILE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_FONT_FILE || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        setWorking(true);
        Utilities.globalQueue.postRunnable(() -> {
            final File tmp = new File(SolegramFonts.dir(), "download.tmp");
            boolean ok = false;
            try (InputStream in = org.telegram.messenger.ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
                 OutputStream os = new FileOutputStream(tmp)) {
                final byte[] buf = new byte[16384];
                int r;
                long total = 0;
                while (in != null && (r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                    total += r;
                    if (total > 8 * 1024 * 1024) {
                        ok = false;
                        break;
                    }
                    ok = true;
                }
            } catch (Throwable e) {
                FileLog.e(e);
                ok = false;
            }
            final boolean done = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (done) {
                    installBase(tmp, getString(R.string.SG_FontCustom));
                } else {
                    failed();
                }
            });
        });
    }

    private boolean isSelected(int position) {
        final String mode = SolegramConfig.appFont;
        if (position == defaultRow) {
            return TextUtils.isEmpty(mode);
        }
        if (position == systemRow) {
            return "system".equals(mode);
        }
        if (position >= firstFontRow && position <= lastFontRow) {
            return "custom".equals(mode) && CURATED[position - firstFontRow][0].equals(SolegramConfig.appFontName);
        }
        return false;
    }

    // ------------------------------------------------------------------ the preview card

    private class FontPreview extends FrameLayout {
        private final TextView big;
        private final TextView small;

        FontPreview(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(16), AndroidUtilities.dp(22), AndroidUtilities.dp(18));
            final LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            big = new TextView(context);
            big.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
            big.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            big.setText("Solegram");
            col.addView(big);
            small = new TextView(context);
            small.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
            small.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            small.setText("Съешь ещё этих мягких булок 1234567890");
            col.addView(small, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            addView(col);
            setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            refresh();
        }

        void refresh() {
            // getTypeface routes through the chosen font (SolegramFonts), so the preview shows it live
            big.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
            small.setTypeface(AndroidUtilities.getTypeface("fonts/rregular.ttf"));
            big.invalidate();
            small.invalidate();
        }
    }

    // ------------------------------------------------------------------ list

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int t = holder.getItemViewType();
            return t == 1 || t == 2;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    preview = new FontPreview(context);
                    view = preview;
                    break;
                case 2:
                    view = new HeaderCell(context);
                    break;
                case 3:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case 1:
                default:
                    view = new TextCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 2:
                    ((HeaderCell) holder.itemView).setText(getString(R.string.SG_FontChoose));
                    break;
                case 3:
                    TextInfoPrivacyCell info = (TextInfoPrivacyCell) holder.itemView;
                    info.setText(getString(R.string.SG_FontInfo));
                    info.setBackground(Theme.getThemedDrawableByKey(context, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                case 1:
                    TextCell cell = (TextCell) holder.itemView;
                    cell.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    String title;
                    boolean divider = true;
                    if (position == defaultRow) {
                        title = getString(R.string.SG_FontDefault);
                    } else if (position == systemRow) {
                        title = getString(R.string.SG_SystemFont);
                    } else if (position == fileRow) {
                        title = getString(R.string.SG_FontFromFile);
                    } else if (position == linkRow) {
                        title = getString(R.string.SG_FontFromLink);
                        divider = false;
                    } else {
                        title = CURATED[position - firstFontRow][0];
                    }
                    if (position == fileRow || position == linkRow) {
                        cell.setTextAndIcon(title, position == fileRow ? R.drawable.msg_photos : R.drawable.msg_link2, divider);
                    } else {
                        final boolean sel = isSelected(position);
                        cell.setTextAndCheckAndIcon(title, sel, 0, true);
                    }
                    break;
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == previewRow) {
                return 0;
            }
            if (position == fontsHeaderRow) {
                return 2;
            }
            if (position == infoRow) {
                return 3;
            }
            return 1;
        }
    }
}
