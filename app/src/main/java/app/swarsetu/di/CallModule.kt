package app.swarsetu.di

import app.swarsetu.call.CallManager
import app.swarsetu.stt.SttEngine
import app.swarsetu.stt.sravaani.Sravaani10Engine
import app.swarsetu.ui.call.LiveCallViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val callModule = module {
    single {
        val sttEngine = get<SttEngine>()
        val sravaani10 = (sttEngine as? Sravaani10Engine) ?: Sravaani10Engine(androidContext(), get())
        CallManager(
            context = androidContext(),
            scope = get(),
            sravaaniEngine = sravaani10,
            translatorEngine = get(),
            ttsManager = get(),
            meshController = get(),
            identity = get(),
        )
    }

    viewModel { LiveCallViewModel(get(), get()) }
}
