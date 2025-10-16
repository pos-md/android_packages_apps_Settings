/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settings.location;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.OP_MOCK_LOCATION;

import static com.android.settings.development.Flags.FLAG_DEPRECATE_LIST_ACTIVITY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settings.SettingsActivity;
import com.android.settings.SubSettings;
import com.android.settings.development.AppPicker;
import com.android.settings.development.DevelopmentAppPicker;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class MockLocationAppPreferenceControllerTest {

    private static final int REQUEST_MOCK_LOCATION_APP = 1000;
    private static final String CURRENT_APP = "com.example.current";
    private static final String NEW_APP = "com.example.newapp";
    private static final int CURRENT_APP_UID = 10001;
    private static final int NEW_APP_UID = 10002;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private LocationSettings mFragment;
    @Mock
    private AppOpsManager mAppOpsManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PreferenceScreen mScreen;

    private Context mContext;
    private Preference mPreference;
    private MockLocationAppPreferenceController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override
            public Object getSystemService(String name) {
                return Context.APP_OPS_SERVICE.equals(name)
                        ? mAppOpsManager : super.getSystemService(name);
            }

            @Override
            public PackageManager getPackageManager() {
                return mPackageManager;
            }
        };
        mController = new MockLocationAppPreferenceController(mContext, mFragment);
        mPreference = new Preference(mContext);
        mPreference.setKey(mController.getPreferenceKey());
        when(mScreen.findPreference(mController.getPreferenceKey())).thenReturn(mPreference);
        mController.displayPreference(mScreen);
    }

    @Test
    public void updateState_appSelected_showsAppLabel()
            throws PackageManager.NameNotFoundException {
        setCurrentMockLocationApp();
        final ApplicationInfo app = stubApplication(CURRENT_APP, CURRENT_APP_UID);
        when(mPackageManager.getApplicationLabel(app)).thenReturn("Mock location test app");

        mController.updateState(mPreference);

        assertThat(mPreference.getSummary().toString()).isEqualTo(mContext.getString(
                com.android.settingslib.R.string.mock_location_app_set, "Mock location test app"));
    }

    @Test
    public void updateState_noAppSelected_showsDefaultSummary() {
        when(mAppOpsManager.getPackagesForOps(any(int[].class)))
                .thenReturn(Collections.emptyList());

        mController.updateState(mPreference);

        assertThat(mPreference.getSummary().toString()).isEqualTo(
                mContext.getString(com.android.settingslib.R.string.mock_location_app_not_set));
    }

    @Test
    public void onActivityResult_newAppSelected_revokesPreviousAppAndAllowsNewApp()
            throws PackageManager.NameNotFoundException {
        setCurrentMockLocationApp();
        stubApplication(CURRENT_APP, CURRENT_APP_UID);
        stubApplication(NEW_APP, NEW_APP_UID);

        assertThat(mController.onActivityResult(
                REQUEST_MOCK_LOCATION_APP, Activity.RESULT_OK, new Intent(NEW_APP))).isTrue();

        final InOrder order = inOrder(mAppOpsManager);
        order.verify(mAppOpsManager).setMode(OP_MOCK_LOCATION, CURRENT_APP_UID, CURRENT_APP,
                MODE_ERRORED);
        order.verify(mAppOpsManager).setMode(OP_MOCK_LOCATION, NEW_APP_UID, NEW_APP, MODE_ALLOWED);
    }

    @Test
    public void onActivityResult_noAppSelected_revokesPreviousApp()
            throws PackageManager.NameNotFoundException {
        setCurrentMockLocationApp();
        stubApplication(CURRENT_APP, CURRENT_APP_UID);

        assertThat(mController.onActivityResult(
                REQUEST_MOCK_LOCATION_APP, Activity.RESULT_OK, new Intent())).isTrue();

        verify(mAppOpsManager).setMode(
                OP_MOCK_LOCATION, CURRENT_APP_UID, CURRENT_APP, MODE_ERRORED);
        verify(mAppOpsManager, never()).setMode(
                eq(OP_MOCK_LOCATION), anyInt(), anyString(), eq(MODE_ALLOWED));
    }

    @Test
    public void onActivityResult_unusableResult_keepsCurrentApp() {
        final Intent selection = new Intent(NEW_APP);

        assertThat(mController.onActivityResult(REQUEST_MOCK_LOCATION_APP + 1,
                Activity.RESULT_OK, selection)).isFalse();
        assertThat(mController.onActivityResult(REQUEST_MOCK_LOCATION_APP,
                Activity.RESULT_CANCELED, selection)).isFalse();
        assertThat(mController.onActivityResult(REQUEST_MOCK_LOCATION_APP,
                Activity.RESULT_OK, null)).isFalse();

        verifyNoInteractions(mAppOpsManager);
    }

    @Test
    public void onPreferenceClick_listActivityDeprecated_launchesDevelopmentAppPicker() {
        mSetFlagsRule.enableFlags(FLAG_DEPRECATE_LIST_ACTIVITY);

        assertThat(mPreference.getOnPreferenceClickListener().onPreferenceClick(mPreference))
                .isTrue();

        final ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mFragment).startActivityForResult(captor.capture(), eq(REQUEST_MOCK_LOCATION_APP));
        final Intent intent = captor.getValue();
        assertThat(intent.getComponent().getClassName()).isEqualTo(SubSettings.class.getName());
        assertThat(intent.getStringExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT))
                .isEqualTo(DevelopmentAppPicker.class.getName());
        assertThat(intent.getBundleExtra(SettingsActivity.EXTRA_SHOW_FRAGMENT_ARGUMENTS)
                .getString(DevelopmentAppPicker.EXTRA_REQUESTING_PERMISSION))
                .isEqualTo(Manifest.permission.ACCESS_MOCK_LOCATION);
    }

    @Test
    public void onPreferenceClick_listActivitySupported_launchesAppPicker() {
        mSetFlagsRule.disableFlags(FLAG_DEPRECATE_LIST_ACTIVITY);

        assertThat(mPreference.getOnPreferenceClickListener().onPreferenceClick(mPreference))
                .isTrue();

        final ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mFragment).startActivityForResult(captor.capture(), eq(REQUEST_MOCK_LOCATION_APP));
        final Intent intent = captor.getValue();
        assertThat(intent.getComponent().getClassName()).isEqualTo(AppPicker.class.getName());
        assertThat(intent.getStringExtra(AppPicker.EXTRA_REQUESTIING_PERMISSION))
                .isEqualTo(Manifest.permission.ACCESS_MOCK_LOCATION);
    }

    @Test
    public void onPreferenceClick_differentPreference_ignoresClick() {
        final Preference otherPreference = new Preference(mContext);
        otherPreference.setKey("other_preference");

        assertThat(mController.onPreferenceClick(otherPreference)).isFalse();

        verifyNoInteractions(mFragment);
    }

    private void setCurrentMockLocationApp() {
        final AppOpsManager.OpEntry op =
                new AppOpsManager.OpEntry(OP_MOCK_LOCATION, MODE_ALLOWED, Collections.emptyMap());
        final AppOpsManager.PackageOps packageOps = new AppOpsManager.PackageOps(
                CURRENT_APP, CURRENT_APP_UID, Collections.singletonList(op));
        when(mAppOpsManager.getPackagesForOps(any(int[].class)))
                .thenReturn(Collections.singletonList(packageOps));
    }

    private ApplicationInfo stubApplication(String packageName, int uid)
            throws PackageManager.NameNotFoundException {
        final ApplicationInfo app = new ApplicationInfo();
        app.uid = uid;
        when(mPackageManager.getApplicationInfo(
                packageName, PackageManager.MATCH_DISABLED_COMPONENTS))
                .thenReturn(app);
        return app;
    }
}
