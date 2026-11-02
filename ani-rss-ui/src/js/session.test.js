import test from 'node:test'
import assert from 'node:assert/strict'
import {shouldClearSession, shouldRenewSession} from './session.js'

test('ordinary forbidden operations and temporary failures keep the login', () => {
    assert.equal(shouldClearSession(403, '无权执行此操作', 'old', 'old'), false)
    assert.equal(shouldClearSession(500, '网络错误', 'old', 'old'), false)
})
test('only authentication failures for the current session log out', () => {
    assert.equal(shouldClearSession(401, '登录已失效', 'old', 'old'), true)
    assert.equal(shouldClearSession(403, '登录已失效', 'old', 'old'), true)
    assert.equal(shouldClearSession(401, '登录已失效', 'old', 'new'), false)
    assert.equal(shouldClearSession(401, '登录已失效', '', ''), false)
})
test('concurrent stale responses cannot overwrite a renewed or newly logged in session', () => {
    assert.equal(shouldRenewSession('old', 'old', 'renewed'), true)
    assert.equal(shouldRenewSession('old', 'new', 'renewed'), false)
    assert.equal(shouldRenewSession('old', 'old', null), false)
})
