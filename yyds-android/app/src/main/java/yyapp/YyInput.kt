package yyapp

import common.BootService
import me.caz.xp.ui.ContextAction
import uiautomator.Const
import uiautomator.ExtSystem

object YyInput {
    fun enableYyInputMethod() {
//        switchYyInput()
        // ime enable com.yyds.auto/common.YyInputService --user 1000
        // ime set com.yyds.auto/common.YyInputService --user 1000
        // settings put secure default_input_method com.yyds.auto/common.YyInputService
        ExtSystem.shell("ime enable ${Const.YY_INPUT_METHOD_ID}")
        ExtSystem.shell("ime set ${Const.YY_INPUT_METHOD_ID}")
        val inputSetting = ExtSystem.shell("settings get secure enabled_input_methods")
        if (!inputSetting.contains(Const.YY_INPUT_METHOD_ID)) {
            ExtSystem.shell("settings put secure enabled_input_methods ${inputSetting}:${Const.YY_INPUT_METHOD_ID}")
        }
        ExtSystem.shell("settings put secure default_input_method ${Const.YY_INPUT_METHOD_ID}")
    }


    fun resetInputMethod() {
        ExtSystem.shell("am broadcast -a yy-input-action -e method M_INPUT_METHOD_RECOVER")
    }
}