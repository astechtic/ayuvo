package com.ayuvo.health.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import com.ayuvo.health.R
import com.ayuvo.health.models.AIProvider
import com.ayuvo.health.models.SpeechProvider
import com.ayuvo.health.ui.theme.AppColors

@Composable
internal fun AIProviderBrandIcon(provider: AIProvider, modifier: Modifier = Modifier, tint: Color = AppColors.Calorie) {
    ProviderBrandIcon(
        drawableRes = provider.logoDrawableRes,
        fallback = Icons.Outlined.Settings,
        tint = tint,
        modifier = modifier
    )
}

@Composable
internal fun SpeechProviderBrandIcon(provider: SpeechProvider, modifier: Modifier = Modifier, tint: Color = AppColors.Calorie) {
    ProviderBrandIcon(
        drawableRes = provider.logoDrawableRes,
        fallback = Icons.Outlined.Settings,
        tint = tint,
        modifier = modifier
    )
}

@Composable
private fun ProviderBrandIcon(
    drawableRes: Int?,
    fallback: ImageVector,
    tint: Color,
    modifier: Modifier
) {
    if (drawableRes != null) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    } else {
        Icon(
            imageVector = fallback,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    }
}

private val AIProvider.logoDrawableRes: Int?
    get() = when (this) {
        AIProvider.GEMINI -> R.drawable.provider_gemini
        AIProvider.VERTEX_AI -> R.drawable.provider_gemini
        AIProvider.OPENAI -> R.drawable.provider_openai
        AIProvider.ANTHROPIC -> R.drawable.provider_anthropic
        AIProvider.XAI -> R.drawable.provider_xai
        AIProvider.OPENROUTER -> R.drawable.provider_openrouter
        AIProvider.TOGETHER_AI -> R.drawable.provider_together
        AIProvider.GROQ -> R.drawable.provider_groq
        AIProvider.HUGGING_FACE -> R.drawable.provider_huggingface
        AIProvider.FIREWORKS -> R.drawable.provider_fireworks
        AIProvider.DEEP_INFRA -> R.drawable.provider_deepinfra
        AIProvider.MISTRAL -> R.drawable.provider_mistral
        AIProvider.DEEPSEEK -> R.drawable.provider_deepseek
        AIProvider.CEREBRAS -> R.drawable.provider_cerebras
        AIProvider.LOCAL_GEMMA -> null
        AIProvider.OLLAMA -> R.drawable.provider_ollama
        AIProvider.CUSTOM_OPENAI -> null
    }

private val SpeechProvider.logoDrawableRes: Int
    get() = when (this) {
        SpeechProvider.NATIVE -> R.drawable.provider_android
        SpeechProvider.LOCAL_WHISPER -> R.drawable.provider_android
        SpeechProvider.GEMINI -> R.drawable.provider_gemini
        SpeechProvider.OPENAI -> R.drawable.provider_openai
        SpeechProvider.GROQ -> R.drawable.provider_groq
        SpeechProvider.MISTRAL -> R.drawable.provider_mistral
        SpeechProvider.DEEPGRAM -> R.drawable.provider_deepgram
        SpeechProvider.ASSEMBLY_AI -> R.drawable.provider_assemblyai
    }
