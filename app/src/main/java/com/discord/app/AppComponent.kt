package com.discord.app

import rx.subjects.Subject

interface AppComponent {
    val unsubscribeSignal: Subject<Void, Void>
}
