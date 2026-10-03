package dev.typenil.vpnclient.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticStartPolicyTest {
    private data class Case(
        val request: AutomaticStartRequest, val desired: Boolean,
        val prepared: Boolean, val selected: Boolean, val branch: AutomaticStartBranch,
    )

    @Test fun `system always-on ignores desire but requires consent and selection`() {
        val cases = listOf(
            Case(AutomaticStartRequest.AlwaysOn, false, true, true, AutomaticStartBranch.AlwaysOn),
            Case(AutomaticStartRequest.AlwaysOn, true, true, true, AutomaticStartBranch.AlwaysOn),
            Case(AutomaticStartRequest.AlwaysOn, false, false, true, AutomaticStartBranch.MissingPrerequisites),
            Case(AutomaticStartRequest.AlwaysOn, true, false, true, AutomaticStartBranch.MissingPrerequisites),
            Case(AutomaticStartRequest.AlwaysOn, false, true, false, AutomaticStartBranch.MissingPrerequisites),
            Case(AutomaticStartRequest.AlwaysOn, true, true, false, AutomaticStartBranch.MissingPrerequisites),
            Case(AutomaticStartRequest.AlwaysOn, false, false, false, AutomaticStartBranch.MissingPrerequisites),
            Case(AutomaticStartRequest.Restore, true, true, true, AutomaticStartBranch.Restore),
            Case(AutomaticStartRequest.Restore, true, false, false, AutomaticStartBranch.Restore),
            Case(AutomaticStartRequest.Restore, false, true, true, AutomaticStartBranch.Stop),
            Case(AutomaticStartRequest.Restore, false, false, false, AutomaticStartBranch.Stop),
            Case(AutomaticStartRequest.Stray, true, true, true, AutomaticStartBranch.Stop),
            Case(AutomaticStartRequest.Stray, false, false, false, AutomaticStartBranch.Stop),
        )
        cases.forEach { case ->
            assertEquals(case.toString(), case.branch,
                automaticStartBranch(case.request, case.desired, case.prepared, case.selected))
        }
    }
}
