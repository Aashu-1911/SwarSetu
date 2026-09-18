package app.swarsetu.di


import app.swarsetu.ui.blocked.BlockedUsersViewModel
import app.swarsetu.ui.chat.ChatViewModel
import app.swarsetu.ui.chat.MessageDetailsViewModel
import app.swarsetu.ui.chatlist.ChatListViewModel
import app.swarsetu.ui.contacts.ContactsViewModel
import app.swarsetu.ui.diagnostics.CrashLogViewModel
import app.swarsetu.ui.diagnostics.DiagnosticsViewModel
import app.swarsetu.ui.group.GroupDetailsViewModel
import app.swarsetu.ui.profile.ProfileDetailsViewModel
import app.swarsetu.ui.profile.ProfileViewModel

import app.swarsetu.ui.requests.MessageRequestsViewModel
import app.swarsetu.ui.verify.VerifyContactViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val uiModule =
    module {
        // ChatViewModel takes the conversationId (the Nearby room, a peer's node id, or a group id) as a
        // runtime param; the rest (incl. GroupRepository) are resolved by type.
        viewModel { params ->
            ChatViewModel(
                params.get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),

                androidContext(),
                get(), // ttsManager
                get(), // voiceMessageAdapter
                get(), // voiceController
                get(), // sttPipeline
                get(), // translatorEngine
            )
        }
        viewModel {
            ChatListViewModel(get(), get(), get(), get(), get(), get(), androidContext())
        }
        viewModel { ContactsViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { DiagnosticsViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { CrashLogViewModel(get()) }
        viewModel { ProfileViewModel(get(), get(), get(), get(), get()) }
        // ProfileDetailsViewModel takes the tapped peer's node id as a runtime param.
        viewModel { params -> ProfileDetailsViewModel(params.get(), get(), get(), get(), get()) }
        // MessageDetailsViewModel takes the long-pressed message's id as a runtime param.
        viewModel { params -> MessageDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), get()) }
        // GroupDetailsViewModel takes the group id as a runtime param; the rest are resolved by type.
        viewModel { params ->
            GroupDetailsViewModel(params.get(), get(), get(), get(), get(), get(), get(), androidContext())
        }
        viewModel { BlockedUsersViewModel(get(), get()) }
        viewModel { MessageRequestsViewModel(get(), get(), get(), get(), get(), androidContext()) }
        viewModel { VerifyContactViewModel(get(), get()) }

    }
