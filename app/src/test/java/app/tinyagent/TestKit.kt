package app.tinyagent

const val W = 1080
const val H = 2400

fun node(
    text: String? = null, desc: String? = null, hint: String? = null, id: String? = null, cls: String = "android.view.View",
    l: Int = 0, t: Int = 0, r: Int = 100, b: Int = 100, visible: Boolean = true, click: Boolean = false, edit: Boolean = false,
    checkable: Boolean = false, checked: Boolean = false, fwd: Boolean = false, back: Boolean = false,
    password: Boolean = false, focused: Boolean = false, kids: List<N> = emptyList(),
) = N(text, desc, hint, id, cls, l, t, r, b, visible, click, edit, checkable, checked, fwd, back, password, focused, kids)

fun win(root: N, label: String = "App", pkg: String = "com.app", layer: Int = 1, ime: Boolean = false,
        own: Boolean = false, active: Boolean = true) = Win(root, pkg, label, layer, ime, own, active)

/** A full-screen app window holding [kids]. */
fun app(vararg kids: N, label: String = "App", pkg: String = "com.app", layer: Int = 1, active: Boolean = true, fwd: Boolean = false) =
    win(node(cls = "android.widget.FrameLayout", l = 0, t = 0, r = W, b = H, fwd = fwd, kids = kids.toList()), label, pkg, layer, active = active)

fun button(label: String, t: Int, l: Int = 40, r: Int = l + 200) = node(text = label, cls = "android.widget.Button", l = l, t = t, r = r, b = t + 80, click = true)
fun textNode(label: String, t: Int, l: Int = 40) = node(text = label, cls = "android.widget.TextView", l = l, t = t, r = l + 400, b = t + 60)
fun input(hint: String, t: Int, text: String? = null, password: Boolean = false, focused: Boolean = false) =
    node(text = text, hint = hint, cls = "android.widget.EditText", l = 40, t = t, r = 1000, b = t + 100, click = true, edit = true,
        password = password, focused = focused)

fun digest(vararg wins: Win, mode: Mode = Mode.ACT, max: Int = 200) = Screen.digest(wins.toList(), mode, max, W, H)
