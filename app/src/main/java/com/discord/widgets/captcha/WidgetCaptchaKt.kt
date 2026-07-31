@file:JvmName("WidgetCaptchaKt")

package com.discord.widgets.captcha

import com.discord.utilities.captcha.CaptchaHelper
import com.discord.utilities.error.RestApiError

/** Whether the API answered this request with a captcha challenge. */
fun RestApiError.isCaptchaError(): Boolean = CaptchaHelper.CAPTCHA_KEY in messages
