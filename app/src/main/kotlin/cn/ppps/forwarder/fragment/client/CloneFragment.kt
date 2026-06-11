package cn.ppps.forwarder.fragment.client

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientCloneBinding
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.Base64
import cn.ppps.forwarder.utils.CloneQrUtils
import cn.ppps.forwarder.utils.CommonUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.KEY_DEFAULT_SELECTION
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.RSACrypt
import cn.ppps.forwarder.utils.SM4Crypt
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.reflect.TypeToken
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.google.zxing.integration.android.IntentIntegrator
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.cache.model.CacheMode
import com.xuexiang.xhttp2.callback.SimpleCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.annotation.AutoWired
import com.xuexiang.xrouter.launcher.XRouter
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.data.ConvertTools
import com.xuexiang.xutil.file.FileIOUtils
import com.xuexiang.xutil.file.FileUtils
import com.xuexiang.xutil.resource.ResUtils.getStringArray
import java.io.File
import java.util.Date

@Suppress("PrivatePropertyName")
@Page(name = "一键换新机")
class CloneFragment : BaseFragment<FragmentClientCloneBinding?>(), View.OnClickListener {

    private val TAG: String = CloneFragment::class.java.simpleName
    private var backupPath: String? = null
    private val backupFile = "SmsForwarder.json"
    private var pushCountDownHelper: CountDownButtonHelper? = null
    private var pullCountDownHelper: CountDownButtonHelper? = null
    private var exportCountDownHelper: CountDownButtonHelper? = null
    private var importCountDownHelper: CountDownButtonHelper? = null
    private val qrScannedChunks = linkedMapOf<Int, CloneQrUtils.QrChunk>()
    private var qrScanSessionId: String? = null
    private var qrScanTotal: Int = 0
    private var qrScanSha256: String? = null

    @JvmField
    @AutoWired(name = KEY_DEFAULT_SELECTION)
    var defaultSelection: Int = 0

    override fun initArgs() {
        XRouter.getInstance().inject(this)
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientCloneBinding {
        return FragmentClientCloneBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_clone)
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        // 申请储存权限
        XXPermissions.with(this)
            .permission(PermissionLists.getManageExternalStoragePermission())
            .request(object : OnPermissionCallback {
                @SuppressLint("SetTextI18n")
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        // 判断请求失败的权限是否被用户勾选了不再询问的选项
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            // 如果是被永久拒绝就跳转到应用权限系统设置页面
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        // 处理权限请求失败的逻辑
                        binding!!.tvBackupPath.text = getString(R.string.storage_permission_tips)
                        return
                    }
                    // 处理权限请求成功的逻辑
                    backupPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
                    binding!!.tvBackupPath.text = backupPath + File.separator + backupFile

                }
            })

        binding!!.tabBar.setTabTitles(getStringArray(R.array.clone_type_option))
        binding!!.tabBar.setOnTabClickListener { _, position ->
            //XToastUtils.toast("点击了$title--$position")
            showCloneMode(position)
        }
        //通用设置界面跳转时只使用离线模式
        if (defaultSelection == 1) {
            binding!!.tabBar.visibility = View.GONE
            showCloneMode(1)
        }

        //按钮增加倒计时，避免重复点击
        pushCountDownHelper = CountDownButtonHelper(binding!!.btnPush, SettingUtils.requestTimeout)
        pushCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPush.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPush.text = getString(R.string.push)
            }
        })
        pullCountDownHelper = CountDownButtonHelper(binding!!.btnPull, SettingUtils.requestTimeout)
        pullCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnPull.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnPull.text = getString(R.string.pull)
            }
        })
        exportCountDownHelper = CountDownButtonHelper(binding!!.btnExport, 3)
        exportCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnExport.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnExport.text = getString(R.string.export)
            }
        })
        importCountDownHelper = CountDownButtonHelper(binding!!.btnImport, 3)
        importCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnImport.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnImport.text = getString(R.string.imports)
            }
        })
    }

    override fun initListeners() {
        binding!!.btnPush.setOnClickListener(this)
        binding!!.btnPull.setOnClickListener(this)
        binding!!.btnExport.setOnClickListener(this)
        binding!!.btnImport.setOnClickListener(this)
        binding!!.btnQrExport.setOnClickListener(this)
        binding!!.btnQrImport.setOnClickListener(this)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            //推送配置
            R.id.btn_push -> pushData()
            //拉取配置
            R.id.btn_pull -> pullData()
            //生成二维码
            R.id.btn_qr_export -> confirmQrExport()
            //扫码导入
            R.id.btn_qr_import -> startQrImport()
            //导出配置
            R.id.btn_export -> {
                try {
                    exportCountDownHelper?.start()
                    val file = File(backupPath + File.separator + backupFile)
                    //判断文件是否存在，存在则在创建之前删除
                    FileUtils.createFileByDeleteOldFile(file)
                    val cloneInfo = HttpServerUtils.exportSettings()
                    val jsonStr = Gson().toJson(cloneInfo)
                    Log.d(TAG, "jsonStr = $jsonStr")
                    if (FileIOUtils.writeFileFromString(file, jsonStr)) {
                        XToastUtils.success(getString(R.string.export_succeeded))
                    } else {
                        binding!!.tvExport.text = getString(R.string.export_failed)
                        XToastUtils.error(getString(R.string.export_failed))
                    }
                } catch (e: Exception) {
                    XToastUtils.error(String.format(getString(R.string.export_failed_tips), e.message))
                }
            }
            //导入配置
            R.id.btn_import -> {
                try {
                    importCountDownHelper?.start()
                    val file = File(backupPath + File.separator + backupFile)
                    //判断文件是否存在
                    if (!FileUtils.isFileExists(file)) {
                        XToastUtils.error(getString(R.string.import_failed_file_not_exist))
                        return
                    }

                    val jsonStr = FileIOUtils.readFile2String(file)
                    Log.d(TAG, "jsonStr = $jsonStr")
                    if (TextUtils.isEmpty(jsonStr)) {
                        XToastUtils.error(getString(R.string.import_failed))
                        return
                    }

                    //替换Date字段为当前时间
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val gson = builder.create()
                    val cloneInfo = gson.fromJson(jsonStr, CloneInfo::class.java)
                    Log.d(TAG, "cloneInfo = $cloneInfo")

                    //判断版本是否一致
                    HttpServerUtils.compareVersion(cloneInfo)

                    if (HttpServerUtils.restoreSettings(cloneInfo)) {
                        MaterialDialog.Builder(requireContext())
                            .iconRes(R.drawable.icon_api_clone)
                            .title(R.string.clone)
                            .content(R.string.import_succeeded)
                            .cancelable(false)
                            .positiveText(R.string.confirm)
                            .onPositive { _: MaterialDialog?, _: DialogAction? ->
                                val intent = Intent(App.context, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(intent)
                            }
                            .show()
                    } else {
                        XToastUtils.error(getString(R.string.import_failed))
                    }
                } catch (e: Exception) {
                    XToastUtils.error(String.format(getString(R.string.import_failed_tips), e.message))
                }
            }
        }
    }

    private fun showCloneMode(position: Int) {
        binding!!.layoutNetwork.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding!!.layoutOffline.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding!!.layoutQr.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun confirmQrExport() {
        MaterialDialog.Builder(requireContext())
            .iconRes(R.drawable.icon_api_clone)
            .title(R.string.clone_qr_export_title)
            .content(R.string.clone_qr_sensitive_warning)
            .positiveText(R.string.confirm)
            .negativeText(R.string.cancel)
            .onPositive { _: MaterialDialog?, _: DialogAction? -> generateQrExport() }
            .show()
    }

    private fun generateQrExport() {
        val root = binding?.root ?: return
        Thread({
            try {
                val cloneInfo = HttpServerUtils.exportSettings()
                val jsonStr = Gson().toJson(cloneInfo)
                val qrPackage = CloneQrUtils.buildPackage(jsonStr)
                Log.i(TAG, "Generated clone QR session=${qrPackage.sessionId} total=${qrPackage.total}")
                root.post {
                    if (isAdded && binding != null) {
                        showQrExportDialog(qrPackage)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "generateQrExport error: ${e.message}")
                val message = e.message
                root.post {
                    if (isAdded && binding != null) {
                        XToastUtils.error(String.format(getString(R.string.export_failed_tips), message))
                    }
                }
            }
        }, "clone-qr-export").start()
    }

    private fun showQrExportDialog(qrPackage: CloneQrUtils.QrPackage) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_clone_qr_export, null)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tv_qr_progress)
        val tvSummary = dialogView.findViewById<TextView>(R.id.tv_qr_summary)
        val ivQrCode = dialogView.findViewById<ImageView>(R.id.iv_qr_code)
        val btnPrev = dialogView.findViewById<Button>(R.id.btn_qr_prev)
        val btnNext = dialogView.findViewById<Button>(R.id.btn_qr_next)
        var index = 0
        var currentBitmap: Bitmap? = null
        var renderToken = 0
        var dialogDismissed = false

        fun recycleCurrentBitmap() {
            ivQrCode.setImageDrawable(null)
            currentBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            currentBitmap = null
        }

        fun updateButtons(enabled: Boolean) {
            btnPrev.isEnabled = enabled && index > 0
            btnNext.isEnabled = enabled && index < qrPackage.total - 1
        }

        fun renderQr() {
            val targetIndex = index
            val token = ++renderToken
            tvProgress.text = String.format(getString(R.string.clone_qr_progress), index + 1, qrPackage.total)
            tvSummary.text = getString(R.string.clone_qr_summary)
            updateButtons(false)

            Thread({
                var bitmap: Bitmap? = null
                try {
                    bitmap = CloneQrUtils.createQrBitmap(qrPackage.chunks[targetIndex])
                    val result = bitmap
                    val root = binding?.root
                    if (root == null) {
                        if (result?.isRecycled == false) {
                            result.recycle()
                        }
                    } else {
                        root.post {
                            if (!isAdded || binding == null || dialogDismissed || token != renderToken || result == null) {
                                if (result?.isRecycled == false) {
                                    result.recycle()
                                }
                                return@post
                            }
                            recycleCurrentBitmap()
                            currentBitmap = result
                            ivQrCode.setImageBitmap(result)
                            updateButtons(true)
                        }
                    }
                } catch (e: Exception) {
                    if (bitmap?.isRecycled == false) {
                        bitmap.recycle()
                    }
                    val message = e.message
                    val root = binding?.root
                    if (root != null) {
                        root.post {
                            if (isAdded && binding != null && !dialogDismissed && token == renderToken) {
                                updateButtons(true)
                                XToastUtils.error(String.format(getString(R.string.export_failed_tips), message))
                            }
                        }
                    }
                }
            }, "clone-qr-render").start()
        }

        btnPrev.setOnClickListener {
            if (index > 0) {
                index--
                renderQr()
            }
        }
        btnNext.setOnClickListener {
            if (index < qrPackage.total - 1) {
                index++
                renderQr()
            }
        }
        renderQr()

        val dialog = MaterialDialog.Builder(requireContext())
            .iconRes(R.drawable.icon_api_clone)
            .title(R.string.clone_qr_export_title)
            .customView(dialogView, true)
            .positiveText(R.string.confirm)
            .show()
        dialog.setOnDismissListener {
            dialogDismissed = true
            renderToken++
            recycleCurrentBitmap()
        }
    }

    private fun startQrImport() {
        resetQrScanState()
        XXPermissions.with(this)
            .permission(PermissionLists.getCameraPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        } else {
                            XToastUtils.error(R.string.toast_denied)
                        }
                        return
                    }
                    launchQrScanner()
                }
            })
    }

    @Suppress("DEPRECATION")
    private fun launchQrScanner() {
        val integrator = IntentIntegrator(requireActivity())
        integrator
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.clone_qr_scan_prompt))
            .setBeepEnabled(false)
            .setBarcodeImageEnabled(false)
            .setOrientationLocked(true)
        startActivityForResult(integrator.createScanIntent(), IntentIntegrator.REQUEST_CODE)
    }

    @Deprecated("Deprecated in AndroidX Fragment, required by zxing-android-embedded 3.6.0")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            handleQrScanResult(result.contents)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun handleQrScanResult(contents: String?) {
        if (contents.isNullOrBlank()) {
            XToastUtils.info(getString(R.string.clone_qr_scan_cancelled))
            return
        }

        try {
            val chunk = CloneQrUtils.parseChunk(contents)
            acceptQrChunk(chunk)

            if (qrScannedChunks.size < qrScanTotal) {
                XToastUtils.info(String.format(getString(R.string.clone_qr_scan_progress), qrScannedChunks.size, qrScanTotal))
                continueQrScan()
                return
            }

            val jsonStr = CloneQrUtils.mergeChunks(qrScannedChunks.values)
            restoreQrSettings(jsonStr)
            resetQrScanState()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "handleQrScanResult error: ${e.message}")
            val message = if (e.message == "session mismatch") {
                getString(R.string.clone_qr_session_mismatch)
            } else {
                String.format(getString(R.string.clone_qr_invalid), e.message)
            }
            XToastUtils.error(message)
            if (qrScanSessionId != null && qrScanTotal > 0 && qrScannedChunks.size < qrScanTotal) {
                continueQrScan()
            }
        }
    }

    private fun acceptQrChunk(chunk: CloneQrUtils.QrChunk) {
        if (qrScanSessionId == null) {
            qrScanSessionId = chunk.sessionId
            qrScanTotal = chunk.total
            qrScanSha256 = chunk.sha256
        }

        require(qrScanSessionId == chunk.sessionId) { "session mismatch" }
        require(qrScanTotal == chunk.total) { "total mismatch" }
        require(qrScanSha256 == chunk.sha256) { "sha256 mismatch" }

        val old = qrScannedChunks[chunk.index]
        if (old != null) {
            require(old.payload == chunk.payload) { "duplicate chunk mismatch" }
            return
        }
        qrScannedChunks[chunk.index] = chunk
    }

    private fun continueQrScan() {
        val root = binding?.root ?: return
        root.postDelayed({
            if (isAdded && binding != null && qrScanTotal > 0 && qrScannedChunks.size < qrScanTotal) {
                launchQrScanner()
            }
        }, 800)
    }

    private fun restoreQrSettings(jsonStr: String) {
        try {
            if (TextUtils.isEmpty(jsonStr)) {
                XToastUtils.error(getString(R.string.import_failed))
                return
            }

            val builder = GsonBuilder()
            builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
            val gson = builder.create()
            val cloneInfo = gson.fromJson(jsonStr, CloneInfo::class.java)
            if (cloneInfo == null) {
                XToastUtils.error(getString(R.string.import_failed))
                return
            }
            Log.d(TAG, "cloneInfo = $cloneInfo")

            HttpServerUtils.compareVersion(cloneInfo)

            if (HttpServerUtils.restoreSettings(cloneInfo)) {
                showQrRestoreSuccessDialog()
            } else {
                XToastUtils.error(getString(R.string.import_failed))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "restoreQrSettings error: ${e.message}")
            XToastUtils.error(String.format(getString(R.string.import_failed_tips), e.message))
        }
    }

    private fun showQrRestoreSuccessDialog() {
        MaterialDialog.Builder(requireContext())
            .iconRes(R.drawable.icon_api_clone)
            .title(R.string.clone_qr_import_title)
            .content(R.string.clone_qr_import_succeeded_reminder)
            .cancelable(false)
            .positiveText(R.string.confirm)
            .onPositive { _: MaterialDialog?, _: DialogAction? -> restartApp() }
            .show()
    }

    private fun resetQrScanState() {
        qrScannedChunks.clear()
        qrScanSessionId = null
        qrScanTotal = 0
        qrScanSha256 = null
    }

    private fun restartApp() {
        val intent = Intent(App.context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    //推送配置
    private fun pushData() {
        if (!CommonUtils.checkUrl(HttpServerUtils.serverAddress)) {
            XToastUtils.error(getString(R.string.invalid_service_address))
            return
        }

        pushCountDownHelper?.start()

        val requestUrl: String = HttpServerUtils.serverAddress + "/clone/push"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        val clientSignKey = HttpServerUtils.clientSignKey
        if (!TextUtils.isEmpty(clientSignKey)) {
            msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        }
        msgMap["data"] = HttpServerUtils.exportSettings()

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeOut((SettingUtils.requestTimeout * 1000).toLong()) //超时时间10s
            .cacheMode(CacheMode.NO_CACHE).timeStamp(true)

        when (HttpServerUtils.clientSafetyMeasures) {
            2 -> {
                val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                try {
                    requestMsg = Base64.encode(requestMsg.toByteArray())
                    requestMsg = RSACrypt.encryptByPublicKey(requestMsg, publicKey)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    pushCountDownHelper?.finish()
                    return
                }
                postRequest.upString(requestMsg)
            }

            3 -> {
                try {
                    val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                    //requestMsg = Base64.encode(requestMsg.toByteArray())
                    val encryptCBC = SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key)
                    requestMsg = ConvertTools.bytes2HexString(encryptCBC)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    pushCountDownHelper?.finish()
                    return
                }
                postRequest.upString(requestMsg)
            }

            else -> {
                postRequest.upJson(requestMsg)
            }
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                pushCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 2) {
                        val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                        json = RSACrypt.decryptByPublicKey(json, publicKey)
                        json = String(Base64.decode(json))
                    } else if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                        val encryptCBC = ConvertTools.hexStringToByteArray(json)
                        val decryptCBC = SM4Crypt.decrypt(encryptCBC, sm4Key)
                        json = String(decryptCBC)
                    }
                    val resp: BaseResponse<String> = Gson().fromJson(json, object : TypeToken<BaseResponse<String>>() {}.type)
                    if (resp.code == 200) {
                        XToastUtils.success(getString(R.string.request_succeeded))
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                pushCountDownHelper?.finish()
            }
        })

    }

    //拉取配置
    private fun pullData() {
        if (!CommonUtils.checkUrl(HttpServerUtils.serverAddress)) {
            XToastUtils.error(getString(R.string.invalid_service_address))
            return
        }

        pullCountDownHelper?.start()

        val requestUrl: String = HttpServerUtils.serverAddress + "/clone/pull"
        Log.i(TAG, "requestUrl:$requestUrl")

        val msgMap: MutableMap<String, Any> = mutableMapOf()
        val timestamp = System.currentTimeMillis()
        msgMap["timestamp"] = timestamp
        val clientSignKey = HttpServerUtils.clientSignKey
        if (!TextUtils.isEmpty(clientSignKey)) {
            msgMap["sign"] = HttpServerUtils.calcSign(timestamp.toString(), clientSignKey)
        }

        val dataMap: MutableMap<String, Any> = mutableMapOf()
        dataMap["version_code"] = AppUtils.getAppVersionCode()
        msgMap["data"] = dataMap

        var requestMsg: String = Gson().toJson(msgMap)
        Log.i(TAG, "requestMsg:$requestMsg")

        val postRequest = XHttp.post(requestUrl).keepJson(true).timeStamp(true)

        when (HttpServerUtils.clientSafetyMeasures) {
            2 -> {
                val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                try {
                    requestMsg = Base64.encode(requestMsg.toByteArray())
                    requestMsg = RSACrypt.encryptByPublicKey(requestMsg, publicKey)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    pullCountDownHelper?.finish()
                    return
                }
                postRequest.upString(requestMsg)
            }

            3 -> {
                try {
                    val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                    //requestMsg = Base64.encode(requestMsg.toByteArray())
                    val encryptCBC = SM4Crypt.encrypt(requestMsg.toByteArray(), sm4Key)
                    requestMsg = ConvertTools.bytes2HexString(encryptCBC)
                    Log.i(TAG, "requestMsg: $requestMsg")
                } catch (e: Exception) {
                    XToastUtils.error(getString(R.string.request_failed) + e.message)
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    pullCountDownHelper?.finish()
                    return
                }
                postRequest.upString(requestMsg)
            }

            else -> {
                postRequest.upJson(requestMsg)
            }
        }

        postRequest.execute(object : SimpleCallBack<String>() {
            override fun onError(e: ApiException) {
                XToastUtils.error(e.displayMessage)
                pullCountDownHelper?.finish()
            }

            override fun onSuccess(response: String) {
                Log.i(TAG, response)
                try {
                    var json = response
                    if (HttpServerUtils.clientSafetyMeasures == 2) {
                        val publicKey = RSACrypt.getPublicKey(HttpServerUtils.clientSignKey)
                        json = RSACrypt.decryptByPublicKey(json, publicKey)
                        json = String(Base64.decode(json))
                    } else if (HttpServerUtils.clientSafetyMeasures == 3) {
                        val sm4Key = ConvertTools.hexStringToByteArray(HttpServerUtils.clientSignKey)
                        val encryptCBC = ConvertTools.hexStringToByteArray(json)
                        val decryptCBC = SM4Crypt.decrypt(encryptCBC, sm4Key)
                        json = String(decryptCBC)
                    }

                    //替换Date字段为当前时间
                    val builder = GsonBuilder()
                    builder.registerTypeAdapter(Date::class.java, JsonDeserializer<Any?> { _, _, _ -> Date() })
                    val gson = builder.create()
                    val resp: BaseResponse<CloneInfo> = gson.fromJson(json, object : TypeToken<BaseResponse<CloneInfo>>() {}.type)
                    if (resp.code == 200) {
                        val cloneInfo = resp.data
                        Log.d(TAG, "cloneInfo = $cloneInfo")

                        if (cloneInfo == null) {
                            XToastUtils.error(getString(R.string.request_failed))
                            return
                        }

                        //判断版本是否一致
                        HttpServerUtils.compareVersion(cloneInfo)

                        if (HttpServerUtils.restoreSettings(cloneInfo)) {
                            XToastUtils.success(getString(R.string.import_succeeded))
                        }
                    } else {
                        XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, e.toString())
                    XToastUtils.error(getString(R.string.request_failed) + response)
                }
                pullCountDownHelper?.finish()
            }
        })

    }

    override fun onDestroyView() {
        if (pushCountDownHelper != null) pushCountDownHelper!!.recycle()
        if (pullCountDownHelper != null) pullCountDownHelper!!.recycle()
        if (exportCountDownHelper != null) exportCountDownHelper!!.recycle()
        if (importCountDownHelper != null) importCountDownHelper!!.recycle()
        super.onDestroyView()
    }
}
