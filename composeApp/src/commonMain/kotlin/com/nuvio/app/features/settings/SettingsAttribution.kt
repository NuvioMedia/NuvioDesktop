package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionPolicy
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_about_based_on_version_format
import nuvio.composeapp.generated.resources.compose_about_made_with
import nuvio.composeapp.generated.resources.compose_about_version_format
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsAttribution(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = if (isTablet) 20.dp else 16.dp),
    ) {
        MemberBrandWordmark(
            height = if (isTablet) 30.dp else 26.dp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(
            modifier = Modifier.height(if (isTablet) 10.dp else 8.dp),
        )

        Text(
            text = stringResource(Res.string.compose_about_made_with),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(
                Res.string.compose_about_version_format,
                AppVersionPolicy.displayVersionName,
                AppVersionPolicy.displayVersionCode,
            ),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AppVersionPolicy.basedOnVersionName?.let { basedOnVersionName ->
            Text(
                text = stringResource(
                    Res.string.compose_about_based_on_version_format,
                    basedOnVersionName,
                ),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
