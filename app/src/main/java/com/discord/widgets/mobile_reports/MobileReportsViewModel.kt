package com.discord.widgets.mobile_reports

class MobileReportsViewModel private constructor() {
    sealed class SubmitState private constructor() {
        object Error : SubmitState()
        object Loading : SubmitState()
        object None : SubmitState()
    }
}
