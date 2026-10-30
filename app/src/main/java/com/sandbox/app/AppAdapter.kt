package com.sandbox.app

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class AppAdapter(
    private val appList: List<SandboxApp>,
    private val onClick: (SandboxApp) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imgIcon: ImageView = view.findViewById(R.id.imgIcon)
        val tvAppName: TextView = view.findViewById(R.id.tvAppName)
        val tvVersion: TextView = view.findViewById(R.id.tvVersion)
        val tvInstallDate: TextView = view.findViewById(R.id.tvInstallDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = appList[position]
        holder.tvAppName.text = app.name
        
        if (app.isInstalled) {
            holder.tvVersion.text = "✅ Installed (v${app.versionName})"
            holder.tvVersion.setTextColor(android.graphics.Color.GREEN)
        } else {
            holder.tvVersion.text = "⬇️ Tap to Install (v${app.versionName})"
            holder.tvVersion.setTextColor(android.graphics.Color.parseColor("#FFA500")) // Orange
        }
        
        holder.tvInstallDate.text = "File added: ${app.installDate}"

        val iconFile = File(app.iconPath)
        if (iconFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
            holder.imgIcon.setImageBitmap(bitmap)
        } else {
            holder.imgIcon.setImageResource(android.R.drawable.sym_def_app_icon)
        }

        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = appList.size
}
