// A response to an older request must never replace or clear a newer login.
export function shouldRenewSession(sent, current, renewed) {
    return Boolean(sent && renewed && sent === current)
}

export function shouldClearSession(code, message, sent, current) {
    return Boolean(sent && sent === current &&
        (code === 401 || code === 403 && message === '登录已失效'))
}
