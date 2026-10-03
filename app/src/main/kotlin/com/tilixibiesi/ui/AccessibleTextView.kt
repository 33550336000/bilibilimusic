package com.tilixibiesi.ui
import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
class AccessibleTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {
    override fun performClick(): Boolean {
        return super.performClick()
    }
}