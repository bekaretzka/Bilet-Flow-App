@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.example.biletflow

import com.example.biletflow.domain.session.SessionStorage
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

class IosSessionStorage(namespace: String) : SessionStorage {
    private val service = "biletflow.session.$namespace"
    private fun query(): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to "tokens",
    )
    override suspend fun read(): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val request = query() + mapOf(kSecReturnData to true, kSecMatchLimit to kSecMatchLimitOne)
        val dictionary = CFBridgingRetain(request) as CFDictionaryRef
        val status = try { SecItemCopyMatching(dictionary, result.ptr) } finally { CFRelease(dictionary) }
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Keychain read failed: $status" }
        val data = CFBridgingRelease(result.value) as NSData
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
            ?: error("Invalid Keychain data")
    }
    override suspend fun write(value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        val base = CFBridgingRetain(query()) as CFDictionaryRef
        try {
            val attrs = CFBridgingRetain(mapOf(kSecValueData to data)) as CFDictionaryRef
            val status = try { SecItemUpdate(base, attrs) } finally { CFRelease(attrs) }
            if (status == errSecItemNotFound) {
                val add = CFBridgingRetain(query() + mapOf(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)) as CFDictionaryRef
                try { check(SecItemAdd(add, null) == errSecSuccess) } finally { CFRelease(add) }
            } else check(status == errSecSuccess) { "Keychain write failed: $status" }
        } finally { CFRelease(base) }
    }
    override suspend fun clear() {
        val request = CFBridgingRetain(query()) as CFDictionaryRef
        try {
            val status = SecItemDelete(request)
            check(status == errSecSuccess || status == errSecItemNotFound)
        } finally { CFRelease(request) }
    }
}
