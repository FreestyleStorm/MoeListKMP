package com.axiel7.moelist.screens.userlist.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.axiel7.moelist.data.utils.NumExtensions.toStringPositiveValueOrUnknown

@Composable
fun AnimatedProgressText(
    progress: Int,
    total: Int?,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val label = "$progress/${total.toStringPositiveValueOrUnknown()}"
    AnimatedContent(
        targetState = progress,
        modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(label) },
        transitionSpec = {
            val direction = if (targetState >= initialState) 1 else -1
            (slideInVertically(tween(220)) { it * direction / 2 } + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(tween(180)) { -it * direction / 2 } + fadeOut(tween(120)))
        },
        label = "confirmed list progress",
    ) { value ->
        Text("$value/${total.toStringPositiveValueOrUnknown()}", style = style)
    }
}
