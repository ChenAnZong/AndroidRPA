package com.tencent.yyds

import android.content.Intent
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tencent.yyds.databinding.RowProjectBinding
import me.caz.xp.ui.ContextAction
import pyengine.PyEngine
import pyengine.YyProject
import uiautomator.ExtSystem
import kotlin.concurrent.thread


class YypListAdapter(var moduleList: List<YyProject>) :
        RecyclerView.Adapter<YypListViewHolder>() {

    private lateinit var holder: YypListViewHolder

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): YypListViewHolder {
        val yypRowBinding =
                RowProjectBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        holder =  YypListViewHolder(yypRowBinding)
        return holder
    }

    override fun onBindViewHolder(holder: YypListViewHolder, position: Int) {
        val yydsAutoProject = moduleList[position]
        holder.yypRowBinding.yyp = yydsAutoProject

        holder.yypRowBinding.imageIconCon.setOnClickListener {
                thread {
                    if (PyEngine.getProjectRunningStatus()?.first == true) {
                        PyEngine.abortProject()
                        ContextAction.uiThread {
                            holder.yypRowBinding.imageIconCon.setImageDrawable(ContextCompat.getDrawable(App.app, R.drawable.ic_start))
                        }
                    } else {
                        ContextAction.uiThread {
                            holder.yypRowBinding.imageIconCon.setImageDrawable(ContextCompat.getDrawable(App.app, R.drawable.ic_stop))
                        }
                        yydsAutoProject.start()
                    }
                    // 是否已经停止运行
                    while (true) {
                        SystemClock.sleep(1500)
                        val status = PyEngine.getProjectRunningStatus()
                        ExtSystem.printDebugLog("运行状态:${status}")
                        val runningProjectName = status?.second
                        if (status == null || !status.first || runningProjectName == null || runningProjectName!= yydsAutoProject.folderName) {
                            ContextAction.uiThread {
                                holder.yypRowBinding.imageIconCon.setImageDrawable(ContextCompat.getDrawable(App.app, R.drawable.ic_start))
                            }
                            break
                        }
                    }
                }
            }

        holder.yypRowBinding.imageIconConfig.setOnClickListener {
            App.app.startActivity(
                Intent(App.app, ProjectConfigActivity::class.java)
                .apply { putExtra(App.KEY_PROJECT, yydsAutoProject)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }, )
        }
        holder.yypRowBinding.imageIconMore.setOnClickListener { holder.showBottomSheetMenu(it) }
        holder.yypRowBinding.executePendingBindings()

        // 刷新运行状态
        thread {
            ExtSystem.printDebugLog("刷新运行状态：", PyEngine.getProjectRunningStatus())
            if (PyEngine.getProjectRunningStatus()?.second == yydsAutoProject.folderName) {
                ContextAction.uiThread {
                    holder.yypRowBinding.imageIconCon.setImageDrawable(ContextCompat.getDrawable(App.app, R.drawable.ic_stop))
                }
            } else {
                ContextAction.uiThread {
                    holder.yypRowBinding.imageIconCon.setImageDrawable(ContextCompat.getDrawable(App.app, R.drawable.ic_start))
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return moduleList.size
    }

}