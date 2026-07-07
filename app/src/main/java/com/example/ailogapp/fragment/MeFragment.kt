package com.example.ailogapp.fragment

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.ailogapp.LogActivity
import com.example.ailogapp.R
import com.example.ailogapp.RecordManagementActivity
import com.example.ailogapp.SettingsActivity
import com.example.ailogapp.databinding.FragmentMeBinding
import com.example.ailogapp.dialog.DeleteRecordsDialog
import com.example.ailogapp.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MeFragment : Fragment() {

    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!

    /** 选择图片作为头像/图标 */
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handlePickedImage(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 加载已保存的自定义头像
        loadCustomAvatar()

        // 点击头像也可修改
        binding.ivAvatar.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        // 录音管理
        binding.layoutRecording.setOnClickListener {
            startActivity(Intent(requireContext(), RecordManagementActivity::class.java))
        }

        // 设置
        binding.layoutSettings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        // 删除录音
        binding.layoutDeleteRecords.setOnClickListener {
            DeleteRecordsDialog { count ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.delete_success, count),
                    Toast.LENGTH_SHORT
                ).show()
            }.show(parentFragmentManager, DeleteRecordsDialog.TAG)
        }

        // 运行日志
        binding.layoutLog.setOnClickListener {
            startActivity(Intent(requireContext(), LogActivity::class.java))
        }

        // 权限管理：跳转系统应用详情权限设置页
        binding.layoutPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }

        // 检查更新：若已有新版本待下载，则直接下载；否则发起检查
        binding.layoutUpdate.setOnClickListener {
            val url = pendingApkUrl
            if (!url.isNullOrBlank()) {
                downloadAndInstallUpdate(url)
            } else {
                checkForUpdate()
            }
        }

        // 项目主页
        binding.layoutGithub.setOnClickListener {
            val url = getString(R.string.github_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // 关于
        binding.layoutAbout.setOnClickListener {
            val pkgInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            Toast.makeText(
                requireContext(),
                "${getString(R.string.app_name)} v${pkgInfo.versionName}",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 显示版本号
        try {
            val pkgInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            binding.tvVersion.text = "v${pkgInfo.versionName}"
        } catch (e: Exception) {
            binding.tvVersion.text = ""
        }
    }

    /** 待下载的 APK 链接（检查到新版本后暂存，用户点击后触发下载） */
    private var pendingApkUrl: String? = null

    /** 检查 GitHub 最新版本，有新版本时在"检查更新"旁显示提示 */
    private fun checkForUpdate() {
        val ctx = requireContext()
        binding.tvUpdateHint.visibility = View.GONE
        Toast.makeText(ctx, getString(R.string.msg_checking_update), Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            UpdateChecker.checkForUpdate(ctx).collect { state ->
                when (state) {
                    is UpdateChecker.UpdateState.UpdateAvailable -> {
                        pendingApkUrl = state.apkUrl
                        binding.tvUpdateHint.visibility = View.VISIBLE
                        Toast.makeText(ctx, getString(R.string.msg_update_available, state.version), Toast.LENGTH_SHORT).show()
                    }
                    is UpdateChecker.UpdateState.NoUpdate -> {
                        pendingApkUrl = null
                        binding.tvUpdateHint.visibility = View.GONE
                        Toast.makeText(ctx, getString(R.string.msg_already_latest), Toast.LENGTH_SHORT).show()
                    }
                    is UpdateChecker.UpdateState.Error -> {
                        Toast.makeText(ctx, state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    /** 下载 APK 并触发安装（用户点击"检查更新"且已有新版本时触发） */
    private fun downloadAndInstallUpdate(apkUrl: String) {
        val ctx = requireContext()
        if (apkUrl.isBlank()) {
            Toast.makeText(ctx, "未找到 APK 下载链接，请前往 GitHub 手动下载", Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            UpdateChecker.downloadApk(ctx, apkUrl).collect { state ->
                when (state) {
                    is UpdateChecker.UpdateState.Downloading -> {
                        _binding?.tvVersion?.let { it.text = "${state.progress}%" }
                    }
                    is UpdateChecker.UpdateState.Downloaded -> {
                        _binding?.tvVersion?.let {
                            val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                            it.text = "v${pkgInfo.versionName}"
                        }
                        UpdateChecker.installApk(ctx, state.apkPath)
                    }
                    is UpdateChecker.UpdateState.Error -> {
                        _binding?.tvVersion?.let {
                            val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                            it.text = "v${pkgInfo.versionName}"
                        }
                        Toast.makeText(ctx, state.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    /** 加载自定义头像 */
    private fun loadCustomAvatar() {
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                try {
                    val avatarFile = File(requireContext().filesDir, "custom_avatar.png")
                    if (avatarFile.exists()) {
                        BitmapFactory.decodeFile(avatarFile.absolutePath)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.w("MeFragment", "加载自定义头像失败: ${e.message}")
                    null
                }
            }
            if (bmp != null) {
                _binding?.ivAvatar?.setImageBitmap(bmp)
            }
        }
    }

    /** 处理选择的图片：保存为头像 + 更新界面 */
    private fun handlePickedImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            var failed = false
            val ctx = requireContext()
            val result = withContext(Dispatchers.IO) {
                // 在 IO 线程做所有 Bitmap 操作，避免阻塞主线程
                try {
                    // 先测量尺寸，计算 inSampleSize，避免高分辨率照片全尺寸解码导致 OOM
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = calcInSampleSize(bounds, 512, 512)
                    }
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)
                    }
                    if (bmp == null) return@withContext null

                    // 裁剪为圆形头像
                    val circleBmp = cropToCircle(bmp, 256)
                    // 原图已用完，回收释放内存
                    bmp.recycle()

                    // 保存头像文件
                    val avatarFile = File(ctx.filesDir, "custom_avatar.png")
                    FileOutputStream(avatarFile).use { out ->
                        circleBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    circleBmp
                } catch (e: Exception) {
                    Log.e("MeFragment", "处理图片失败", e)
                    failed = true
                    null
                }
            }
            when {
                failed -> Toast.makeText(ctx, getString(R.string.msg_avatar_failed), Toast.LENGTH_SHORT).show()
                result != null -> {
                    _binding?.ivAvatar?.setImageBitmap(result)
                    Toast.makeText(ctx, R.string.me_avatar_changed, Toast.LENGTH_SHORT).show()
                }
                else -> Toast.makeText(ctx, getString(R.string.msg_avatar_unreadable), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 计算 BitmapFactory 的 inSampleSize，使解码后尺寸不超过 reqWidth/reqHeight */
    private fun calcInSampleSize(opts: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = opts.outHeight
        val width = opts.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /** 将 Bitmap 裁剪为圆形 */
    private fun cropToCircle(src: Bitmap, size: Int): Bitmap {
        // 先缩放
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        canvas.drawARGB(0, 0, 0, 0)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, rect, rect, paint)
        // scaled 使用完毕，回收释放内存（createScaledBitmap 可能返回同一实例，需判断）
        if (scaled !== src) scaled.recycle()
        return output
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
