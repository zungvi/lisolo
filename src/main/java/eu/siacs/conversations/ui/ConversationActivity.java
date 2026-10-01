package eu.siacs.conversations.ui;

import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import eu.siacs.conversations.utils.SignupUtils;

public class ConversationActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Must run before super.onCreate so the splash is installed while the window is still
        // being set up. This also swaps in postSplashScreenTheme, which is why the launcher entry
        // can keep Theme.Conversations3.SplashScreen without the rest of the app inheriting it.
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        startActivity(SignupUtils.getRedirectionIntent(this));
        finish();
    }
}
