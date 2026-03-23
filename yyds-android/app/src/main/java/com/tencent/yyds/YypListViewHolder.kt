package com.tencent.yyds

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.tencent.yyds.databinding.RowProjectBinding
import com.tencent.yyds.frag.ScriptFragment
import com.tencent.yyds.widget.AppBanner
import me.caz.xp.ui.ContextAction
import pyengine.YyProjectUtil
import uiautomator.ExtSystem
import yyapp.register.HttpUtil
import java.io.File
import kotlin.concurrent.thread


class YypListViewHolder(val yypRowBinding: RowProjectBinding):
    RecyclerView.ViewHolder(yypRowBinding.root) {

    fun showBottomSheetMenu(anchor: View) {
        val project = yypRowBinding.yyp ?: return
        val context = anchor.context
        val dialog = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)
        val sheetView = View.inflate(context, R.layout.bottom_sheet_project_menu, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<android.widget.TextView>(R.id.tvSheetTitle).text = project.name

        fun dismiss(action: () -> Unit) {
            dialog.dismiss()
            action()
        }

        sheetView.findViewById<View>(R.id.menuBrowseFiles).setOnClickListener {
            dismiss {
                try {
                    context.startActivity(
                        Intent(context, FileBrowserActivity::class.java)
                            .apply { putExtra(App.KEY_PROJECT, project) }
                    )
                } catch (e: Exception) {
                    ExtSystem.printDebugError("打开工程目录:", e)
                    ScriptFragment.INSTANCE?.showBanner(context.getString(R.string.holder_open_failed, e.message), AppBanner.Type.ERROR)
                }
            }
        }

        sheetView.findViewById<View>(R.id.menuPackageApk).setOnClickListener {
            dismiss {
                try {
                    context.startActivity(
                        Intent(context, PackageActivity::class.java)
                            .apply { putExtra(App.KEY_PROJECT, project) }
                    )
                } catch (e: Exception) {
                    ExtSystem.printDebugError("打开打包页面:", e)
                    ScriptFragment.INSTANCE?.showBanner(context.getString(R.string.holder_open_failed, e.message), AppBanner.Type.ERROR)
                }
            }
        }

        sheetView.findViewById<View>(R.id.menuDownload).setOnClickListener {
            dismiss {
                thread {
                    if (project.downloadUrl == null) {
                        ScriptFragment.INSTANCE?.showBanner(context.getString(R.string.holder_download_url_invalid), AppBanner.Type.WARNING)
                    } else {
                        val res = HttpUtil.downloadFile(project.downloadUrl, File(App.app.cacheDir, "down.zip").absolutePath)
                        ExtSystem.printDebugLog("下载工程返回:", res)
                        if (res.first == null) {
                            ScriptFragment.INSTANCE?.showBanner(context.getString(R.string.holder_download_failed, res.second), AppBanner.Type.ERROR)
                        } else {
                            YyProjectUtil.extractZipProject(res.first!!, project.folderName)
                            ScriptFragment.INSTANCE?.showBanner(context.getString(R.string.holder_download_success), AppBanner.Type.SUCCESS)
                        }
                    }
                }
            }
        }

        sheetView.findViewById<View>(R.id.menuResetConfig).setOnClickListener {
            dismiss {
                project.clearConfig()
                ContextAction.toast(context.getString(R.string.holder_config_cleared, project.name))
            }
        }

        sheetView.findViewById<View>(R.id.menuDeleteProject).setOnClickListener {
            dismiss {
                thread {
                    project.delete()
                    ScriptFragment.INSTANCE?.refreshYypProjectListAsync()
                }
            }
        }

        dialog.show()
    }
}

