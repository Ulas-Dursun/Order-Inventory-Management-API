const API = '';

function getToken()    { return localStorage.getItem('cartify_token') || localStorage.getItem('c_token'); }
function getRole()     { return localStorage.getItem('cartify_role') || localStorage.getItem('c_role'); }
function getEmail()    { return localStorage.getItem('cartify_email') || localStorage.getItem('c_email'); }
function isLoggedIn()  { return !!getToken(); }
function isAdmin()     { return getRole() === 'ADMIN'; }

function saveAuth(data) {
    localStorage.setItem('cartify_token', data.token);
    localStorage.setItem('cartify_role',  data.role);
    localStorage.setItem('cartify_email', data.email);
    localStorage.setItem('c_token', data.token);
    localStorage.setItem('c_role',  data.role);
    localStorage.setItem('c_email', data.email);
}

function clearAuth() {
    ['cartify_token', 'cartify_role', 'cartify_email', 'c_token', 'c_role', 'c_email']
        .forEach(k => localStorage.removeItem(k));
}

function authHeaders() {
    return {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + getToken()
    };
}

async function apiFetch(path, options = {}) {
    const res = await fetch(API + path, options);
    if (res.status === 204) return null;
    const data = await res.json().catch(() => null);
    if (!res.ok) throw new Error(data?.message || 'Request failed');
    return data;
}

function toast(msg, type = 'success') {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.className = 'toast-container';
        document.body.appendChild(container);
    }
    const t = document.createElement('div');
    t.className = `toast toast-${type}`;
    t.textContent = msg;
    container.appendChild(t);
    setTimeout(() => t.remove(), 3500);
}

function updateNav() {
    const navUser  = document.getElementById('nav-user');
    const navLogin = document.getElementById('nav-login');
    const navLogout = document.getElementById('nav-logout');
    const navAdmin  = document.getElementById('nav-admin');

    if (!navUser) return;

    if (isLoggedIn()) {
        navUser.textContent  = getEmail() + (isAdmin() ? ' · ADMIN' : '');
        navUser.style.display = 'block';
        if (navLogin)  navLogin.style.display  = 'none';
        if (navLogout) navLogout.style.display  = 'inline-flex';
        if (navAdmin)  navAdmin.style.display   = isAdmin() ? 'inline-flex' : 'none';
    } else {
        navUser.style.display  = 'none';
        if (navLogin)  navLogin.style.display   = 'inline-flex';
        if (navLogout) navLogout.style.display  = 'none';
        if (navAdmin)  navAdmin.style.display   = 'none';
    }
}

function logout() {
    clearAuth();
    window.location.href = '/index.html';
}

function requireAuth() {
    if (!isLoggedIn()) { window.location.href = '/index.html'; return false; }
    return true;
}