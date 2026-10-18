package androidx.hilt.navigation.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import com.example.myplayer.di.AppContainer

@Composable
inline fun <reified VM : ViewModel> hiltViewModel(): VM {
    return AppContainer.getViewModel(VM::class.java)
}
