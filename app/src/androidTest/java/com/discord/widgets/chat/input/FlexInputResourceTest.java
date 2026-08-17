package com.discord.widgets.chat.input;

import static org.junit.Assert.assertNotNull;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.discord.R;
import com.discord.views.VitoImageView;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FlexInputResourceTest {
    @Test
    public void attachmentPreviewPlaceholderInflatesWithoutThemeResolution() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ContextThemeWrapper context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.AppTheme_Dark);
            FrameLayout parent = new FrameLayout(context);
            View inflated = LayoutInflater.from(context)
                    .inflate(com.lytefast.flexinput.R.layout.view_attachment_preview_item, parent, false);
            VitoImageView preview = (VitoImageView) inflated;
            Drawable placeholder = context.getResources().getDrawable(R.drawable.ic_file_24dp);

            placeholder.setBounds(0, 0, 24, 24);
            placeholder.draw(new Canvas(Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)));

            assertNotNull(preview.getHierarchy().getTopLevelDrawable());
        });
    }
}
