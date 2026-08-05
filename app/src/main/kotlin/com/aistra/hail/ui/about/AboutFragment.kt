package com.aistra.hail.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.HUI

class AboutFragment : MainFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    AboutScreen()
                }
            }
        }

    @Preview(showBackground = true)
    @Composable
    fun PreviewAboutScreen() = AppTheme { AboutScreen() }

    @Composable
    private fun AboutScreen() {
        var openLicenseDialog by remember { mutableStateOf(false) }
        if (openLicenseDialog) LicenseDialog { openLicenseDialog = false }
        Column(
            modifier = Modifier.verticalScroll(state = rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))
            Card(
                onClick = { HUI.openLink(HailData.URL_WHY_FREE_SOFTWARE) },
                modifier = Modifier.height(dimensionResource(R.dimen.header_height))
                    .padding(horizontal = dimensionResource(R.dimen.padding_medium))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(
                        dimensionResource(R.dimen.padding_extra_small), Alignment.CenterVertically
                    ), horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(72.dp).background(Color.White, CircleShape),
                        contentScale = ContentScale.None
                    )
                    Text(
                        text = stringResource(R.string.brand_name), style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "${stringResource(R.string.label_version)}: ${HailData.VERSION}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))
            OutlinedCard(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.padding_medium))) {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))) {
                    Text(
                        text = stringResource(R.string.about_hailstone_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_extra_small)))
                    Text(
                        text = buildAnnotatedString {
                            append("I built ")
                            withLink(
                                LinkAnnotation.Url(
                                    HailData.URL_GITHUB,
                                    TextLinkStyles(
                                        style = SpanStyle(color = MaterialTheme.colorScheme.primary)
                                    )
                                )
                            ) { append("Hailstone") }
                            append(" as a fork of ")
                            withLink(
                                LinkAnnotation.Url(
                                    HailData.URL_ORIGINAL_HAIL,
                                    TextLinkStyles(
                                        style = SpanStyle(color = MaterialTheme.colorScheme.primary)
                                    )
                                )
                            ) { append("Hail") }
                            append(
                                ", with a cleaner UI and a simpler, " +
                                    "easier-to-understand user experience."
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ClickableItem(
                    icon = Icons.Outlined.Code,
                    title = R.string.hailstone_github,
                    desc = "gaurav9372/Hailstone"
                ) { HUI.openLink(HailData.URL_GITHUB) }
                ClickableItem(
                    icon = Icons.Outlined.Code,
                    title = R.string.original_hail_app,
                    desc = "aistra0528/Hail"
                ) { HUI.openLink(HailData.URL_ORIGINAL_HAIL) }
            }
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))
            OutlinedCard(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.padding_medium))) {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.padding_medium))) {
                    Text(
                        text = stringResource(R.string.developer_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_extra_small)))
                    Text(
                        text = stringResource(R.string.developer_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ClickableItem(
                    icon = Icons.Outlined.Person, title = R.string.developer_about
                ) { HUI.openLink(HailData.URL_DEVELOPER_ABOUT) }
                ClickableItem(
                    icon = Icons.Outlined.ContactMail, title = R.string.developer_contact
                ) { HUI.openLink(HailData.URL_DEVELOPER_CONTACT) }
                ClickableItem(
                    icon = Icons.Outlined.Article, title = R.string.developer_blogs
                ) { HUI.openLink(HailData.URL_DEVELOPER_BLOG) }
                ClickableItem(
                    icon = Icons.Outlined.Apps, title = R.string.developer_other_apps
                ) { HUI.openLink(HailData.URL_DEVELOPER_APPS) }
            }
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))
            OutlinedCard(modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.padding_medium))) {
                ClickableItem(
                    icon = Icons.Outlined.Favorite, title = R.string.support_development
                ) { HUI.openLink(HailData.URL_DONATE) }
            }
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.padding_medium)))
        }
    }

    @Composable
    private fun ClickableItem(
        icon: ImageVector, @StringRes title: Int, desc: String? = null, onClick: () -> Unit
    ) = Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon, contentDescription = null, modifier = Modifier.padding(
                horizontal = dimensionResource(R.dimen.padding_medium),
                vertical = dimensionResource(if (desc == null) R.dimen.padding_medium else R.dimen.padding_large)
            )
        )
        Column {
            Text(text = stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(text = desc, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun LicenseDialog(onDismiss: () -> Unit) = AlertDialog(
        title = { Text(text = stringResource(R.string.action_licenses)) },
        text = {
            SelectionContainer {
                Text(
                    text = buildAnnotatedString {
                        val lines = resources.openRawResource(R.raw.licenses).bufferedReader().readLines()
                        lines.forEach {
                            if (it.isNotBlank()) withLink(
                                LinkAnnotation.Url(
                                    it.substringAfter(": "),
                                    TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))
                                )
                            ) {
                                append(it.substringBefore(": "))
                            }
                            if (it != lines.last()) append("\n\n")
                        }
                    }, modifier = Modifier.verticalScroll(state = rememberScrollState())
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(text = stringResource(android.R.string.ok)) } })

}
