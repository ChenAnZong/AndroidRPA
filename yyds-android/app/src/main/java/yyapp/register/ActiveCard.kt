package yyapp.register

// 激活卡类型
enum class ActiveCard(val hour: Int, val tag:String) {
    N_ACTIVE(-1, "软件未授权"),
    M_CARD(30 * 24, "授权类型:M"),
    H_CARD(3, "授权类型:H"),
    D_CARD(24, "授权类型:D"),
    Y_CARD(365 * 24, "授权类型:Y"),
    X_CARD(10 * 365 * 24, "授权类型:S")
}

