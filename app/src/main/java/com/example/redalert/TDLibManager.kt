package com.example.redalert

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

object TDLibManager : Client.ResultHandler {

    private const val TAG = "TDLibManager"
    private var client: Client? = null
    
    // The channel ID or username:
    // User requested: "Home Front Command channel (I will provide the ID/username in the code)"
    // We will place a placeholder that the user can change easily.
    const val TARGET_CHANNEL_USERNAME = "PikudHaOref_all" 
    
    private var authorizationState: TdApi.AuthorizationState? = null
    private var appContext: Context? = null
    
    private var serviceStartTime: Long = 0
    private var targetChatId: Long = 0L
    
    private val _authStateFlow = MutableStateFlow<TdApi.AuthorizationState?>(null)
    val authStateFlow = _authStateFlow.asStateFlow()

    fun initialize(context: Context) {
        if (client != null) return
        appContext = context.applicationContext
        
        serviceStartTime = System.currentTimeMillis() / 1000
        
        // Disable TDLib log internally to not flood logcat
        Client.execute(TdApi.SetLogVerbosityLevel(0))

        client = Client.create(this, null, null)
    }

    fun sendAuthInput(input: String) {
        if (authorizationState is TdApi.AuthorizationStateWaitPhoneNumber) {
            client?.send(TdApi.SetAuthenticationPhoneNumber(input, null), this)
        } else if (authorizationState is TdApi.AuthorizationStateWaitCode) {
            client?.send(TdApi.CheckAuthenticationCode(input), this)
        } else if (authorizationState is TdApi.AuthorizationStateWaitPassword) {
            client?.send(TdApi.CheckAuthenticationPassword(input), this)
        }
    }

    override fun onResult(result: TdApi.Object?) {
        when (result?.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                onAuthorizationStateUpdated((result as TdApi.UpdateAuthorizationState).authorizationState)
            }
            TdApi.UpdateNewMessage.CONSTRUCTOR -> {
                val updateNewMessage = result as TdApi.UpdateNewMessage
                handleNewMessage(updateNewMessage.message)
            }
            else -> {
                // Ignore other updates
            }
        }
    }

    private fun onAuthorizationStateUpdated(authState: TdApi.AuthorizationState?) {
        authorizationState = authState
        _authStateFlow.value = authState
        
        when (authState?.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                val request = TdApi.SetTdlibParameters()
                request.apiId = 23610075
                request.apiHash = "4d49b1171e2c91c762f610056b972202"
                request.useMessageDatabase = true
                request.useSecretChats = true
                request.systemLanguageCode = "en"
                request.databaseDirectory = appContext?.filesDir?.absolutePath + "/tdlib"
                request.deviceModel = "Android TV"
                request.systemVersion = "Android"
                request.applicationVersion = "1.0"
                request.databaseEncryptionKey = ByteArray(0) // No encryption

                client?.send(request, this)
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                Log.d(TAG, "TDLib Authorization State Ready!")
                // Might want to resolve the target channel username to get the chat block
                client?.send(TdApi.SearchPublicChat(TARGET_CHANNEL_USERNAME), Client.ResultHandler { result ->
                    if (result is TdApi.Chat) {
                        targetChatId = result.id
                        Log.d(TAG, "Found target chat: ${result.title} [id=${result.id}]")
                    }
                })
            }
            else -> {
                Log.d(TAG, "AuthorizationState: ${authState?.javaClass?.simpleName}")
            }
        }
    }

    private fun handleNewMessage(message: TdApi.Message) {
        // Strictly filter out messages that are not from the target channel
        if (targetChatId == 0L || message.chatId != targetChatId) {
            return
        }

        // Ignore messages older than the service start time to prevent historical spam on login/restart
        if (message.date < serviceStartTime) {
            Log.d(TAG, "Ignoring historical message: ${message.date} < $serviceStartTime")
            return
        }

        // Here we parse the message and show the overlay
        val content = message.content
        if (content is TdApi.MessageText) {
            val text = content.text.text
            Log.d(TAG, "New message text: $text")

            val parsedAlert = AlertParser.parseHebrewAlert(text)
            if (appContext != null && parsedAlert != null) {
                // Broadcast or command OverlayManager to show ALert
                OverlayManager.showAlert(appContext!!, parsedAlert)
            }
        }
    }
    
    fun logOut() {
        client?.send(TdApi.LogOut(), this)
    }

    fun showDemo() {
        val demoAlert = ParsedAlert(
            title = "🧪 התראת ניסיון",
            subTitle = "זוהי התראת בדיקה בלבד!",
            //cities = listOf("ערד", "תל אביב - יפו", "באר שבע")
            cities = listOf("אור יהודה", "אזור", "בית עלמין מורשה", "בני ברק", "בת ים", "גבעת השלושה", "גבעת שמואל", "גבעתיים", "גני תקווה", "גת רימון", "הרצליה - מערב", "הרצליה - מרכז וגליל ים", "חולון", "יהוד מונוסון", "כפר סירקין", "כפר שמריהו", "מגשימים", "מעש", "מקווה ישראל", "מתחם גלילות", "מתחם פי גלילות", "סביון", "פארק אריאל שרון", "פתח תקווה", "קריית אונו", "רמת גן - מזרח", "רמת גן - מערב", "רמת השרון", "תל אביב - דרום העיר ויפו", "תל אביב - מזרח", "תל אביב - מרכז העיר", "תל אביב - עבר הירקון")
        )
        appContext?.let { OverlayManager.showAlert(it, demoAlert) }
    }
}
