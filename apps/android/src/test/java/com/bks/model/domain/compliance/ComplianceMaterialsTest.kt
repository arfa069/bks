package com.bks.model.domain.compliance

import com.bks.model.domain.compliance.BKS_COMPLIANCE
import com.bks.model.domain.compliance.findUnlistedSdkOrNetworkServices
import com.bks.model.domain.compliance.PermissionExplanationId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplianceMaterialsTest {
    @Test
    fun compliancePackageContainsRequiredInternalBetaMaterials() {
        assertTrue(BKS_COMPLIANCE.privacyPolicySections.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.personalInformationItems.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.thirdPartyServices.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.permissionExplanations.isNotEmpty())
        assertTrue(BKS_COMPLIANCE.storeReviewNotes.isNotEmpty())
    }

    @Test
    fun permissionCopyMatchesProductDecisions() {
        val copies = BKS_COMPLIANCE.permissionExplanations.associateBy { it.id }

        val accessibilityCopy = copies.getValue(PermissionExplanationId.AccessibilityBillSync)
        assertEquals("自动记账无障碍服务", accessibilityCopy.title)
        assertEquals(
            "用于用户开启自动记账或开始补录后，在设备本地识别已完成交易。",
            accessibilityCopy.purpose
        )
        assertEquals(
            "自动记账只读可见、非密码、非输入框文字；手动补录仅在账单文字不足时使用本机截图识别，图片不保存、不上传。不点击、不滚动、不启动其他应用；补录结束、断连或十分钟超时后停止。诊断开启时仅自动采集的支付宝批次可保存正文，结果只进入待确认。",
            accessibilityCopy.boundary
        )
        assertEquals(
            "开启后会上传必要交易信息用于分类建议，可选择是否提供更多上下文。",
            copies.getValue(PermissionExplanationId.CloudAi).purpose
        )

        val storeNotes = BKS_COMPLIANCE.storeReviewNotes.associateBy { it.title }
        assertEquals(
            "用户主动开启自动记账或开始补录后才采集；自动记账仅读取可见、非密码、非输入框文字。补录优先读字，仅字段不足且确认是所选应用的安全账单详情时截图进行本机 OCR；截图不保存、不上传，不操作其他应用，不绕过安全窗口限制。补录最长十分钟，结束或断连后停止。诊断正文留存仅适用于自动采集的支付宝批次，所有结果需在待确认中审核。",
            storeNotes.getValue("无障碍审核说明").body
        )
        assertTrue(storeNotes.containsKey("诊断日志审核说明"))
        assertTrue(
            storeNotes.getValue("诊断日志审核说明").body.contains(
                "Release 不显示入口或页面且记录默认关闭"
            )
        )
        assertTrue(
            BKS_COMPLIANCE.personalInformationItems.any { item ->
                item.name == "应用故障与自动记账诊断日志" &&
                    item.requiredState.contains("Release 默认关闭且不显示入口或页面")
            }
        )
        assertTrue(
            BKS_COMPLIANCE.thirdPartyServices.any { service ->
                service.name.contains("Assists") &&
                    service.processingMethod.contains(
                        "只有用户开启诊断日志时，支付宝实际采集批次正文会明文保存在本机诊断目录"
                    ) &&
                    "assists-base" in service.declarationTokens
            }
        )
        assertTrue(
            BKS_COMPLIANCE.thirdPartyServices.any { service ->
                service.name.contains("微信开放平台") &&
                    service.processingMethod.contains("不向微信发送账本") &&
                    "wechat-sdk" in service.declarationTokens
            }
        )
        assertTrue(
            BKS_COMPLIANCE.thirdPartyServices.any { service ->
                service.name.contains("Coil / OkHttp") &&
                    service.processingMethod.contains("最多 10 MB") &&
                    "okhttp" in service.declarationTokens
            }
        )
        assertTrue(
            BKS_COMPLIANCE.personalInformationItems.any { item ->
                item.name == "微信账号标识与资料" &&
                    item.processingMethod.contains("OpenID") &&
                    item.retentionAndDeletion.contains("解绑微信")
            }
        )
        assertTrue(
            BKS_COMPLIANCE.personalInformationItems.any { item ->
                item.name == "随机安装 UUID" && item.retentionAndDeletion.contains("本机数据删除")
            }
        )
    }

    @Test
    fun complianceScannerFlagsUnlistedRiskySdkNames() {
        val findings = findUnlistedSdkOrNetworkServices(
            buildTexts = listOf(
                """implementation("com.umeng.umsdk:analytics:1.0.0")""",
                """exclude(group = "com.google.mlkit", module = "text-recognition-chinese")"""
            ),
            manifestText = "<manifest />",
            declaredServices = BKS_COMPLIANCE.thirdPartyServices
        )

        assertEquals(listOf("umeng"), findings.map { it.token })
    }

    @Test
    fun currentBuildHasNoUnlistedSdkOrNetworkService() {
        val root = projectRoot()
        val findings = findUnlistedSdkOrNetworkServices(
            buildTexts = listOf(
                root.resolve("apps/android/build.gradle.kts").readText(),
                root.resolve("services/backend/build.gradle.kts").readText()
            ),
            manifestText = root.resolve("apps/android/src/main/AndroidManifest.xml").readText(),
            declaredServices = BKS_COMPLIANCE.thirdPartyServices
        )

        assertTrue(findings.isEmpty())
        val versionCatalog = root.resolve("gradle/libs.versions.toml").readText()
        if (versionCatalog.contains("com.tencent.mm.opensdk")) {
            assertTrue(
                BKS_COMPLIANCE.thirdPartyServices.any { service ->
                    "wechat-sdk" in service.declarationTokens
                }
            )
        }
    }

    private fun projectRoot(): File {
        val userDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(userDirectory)) { current ->
            current.parentFile?.absoluteFile
        }
            .first { it.resolve("settings.gradle.kts").exists() }
    }
}
