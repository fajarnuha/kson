package com.fajarnuha.kson.ksp

import com.fajarnuha.kson.Kson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@Kson
interface Member {
    val name: String
    val role: Role
    val address: Address

    interface Address {
        val city: String
    }

    enum class Role { ADMIN, VIEWER }
}

@Kson
interface Team {
    val lead: Member
    val members: List<Member>
    val office: Member.Address
}

class SharedModelTest {
    private val member = """{"name":"a","role":"ADMIN","address":{"city":"x"}}"""

    @Test
    fun decodesTheSameValueThroughEveryRoot() {
        val alone = MemberJson.decode(member)
        val team = TeamJson.decode("""{"lead":$member,"members":[$member],"office":{"city":"x"}}""")
        assertEquals(alone, team.lead)
        assertEquals(alone, team.members.single())
        assertEquals(alone.address, team.office)
        assertSame(alone.javaClass, team.lead.javaClass)
        assertSame(alone.address.javaClass, team.office.javaClass)
    }

    @Test
    fun buildsSharedModelsWithTheOwnersBuilder() {
        val built = teamKson {
            lead {
                name = "a"
                role = Member.Role.ADMIN
                address { city = "x" }
            }
            members { add { name = "a"; role = Member.Role.ADMIN; address { city = "x" } } }
            office { city = "x" }
        }
        val decoded = MemberJson.decode(member)
        assertEquals(decoded, built.lead)
        assertEquals(decoded, built.members.single())
        assertEquals(decoded.address, built.office)
    }
}
