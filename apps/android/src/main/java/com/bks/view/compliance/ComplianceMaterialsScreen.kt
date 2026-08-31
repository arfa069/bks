package com.bks.view.compliance

import com.bks.model.domain.compliance.BKS_COMPLIANCE
import com.bks.model.domain.compliance.ComplianceMaterials
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun ComplianceMaterialsScreen(
    modifier: Modifier = Modifier,
    materials: ComplianceMaterials = BKS_COMPLIANCE,
    onBack: (() -> Unit)? = null
) {
    ComplianceAndPrivacyScreen(
        isDebugBuild = false,
        onBack = onBack ?: {},
        modifier = modifier,
        materials = materials
    )
}
