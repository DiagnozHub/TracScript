package com.brain.tracscript

import java.net.Authenticator
import java.net.PasswordAuthentication
import java.util.concurrent.atomic.AtomicReference

object Socks5Auth {

    @Volatile private var installed = false
    @Volatile private var user: String = ""
    @Volatile private var pass: String = ""

    fun update(userName: String, password: String) {
        user = userName
        pass = password

        if (installed) return

        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                // Для SOCKS5 requestorType ВСЕГДА SERVER — это нормально
                if (user.isBlank() && pass.isBlank()) return null
                return PasswordAuthentication(user, pass.toCharArray())
            }
        })

        installed = true
    }

    fun clear() {
        user = ""
        pass = ""
    }
}

