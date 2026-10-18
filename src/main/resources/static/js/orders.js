let allProducts = [];
let quantities  = {};

function esc(s) {
    return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

document.addEventListener('DOMContentLoaded', () => {
    if (!requireAuth()) return;
    updateNav();
    loadOrders();
});

async function loadOrders() {
    const container = document.getElementById('orders-container');
    try {
        const endpoint = isAdmin() ? '/api/orders/all?size=50' : '/api/orders?size=50';
        const res = await apiFetch(endpoint, { headers: authHeaders() });
        const orders = res.content || res;
        if (!orders.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">🛒</div>
                    <p>No orders yet. Place your first order!</p>
                </div>`;
            return;
        }
        container.innerHTML = orders.map(orderCard).join('');
    } catch(e) {
        container.innerHTML = `<div class="empty-state"><p>${esc(e.message)}</p></div>`;
    }
}

function statusBadge(status) {
    const map = {
        PENDING:   'badge-pending',
        CONFIRMED: 'badge-confirmed',
        SHIPPED:   'badge-shipped',
        DELIVERED: 'badge-delivered',
        CANCELLED: 'badge-cancelled'
    };
    return `<span class="badge ${map[status] || ''}">${esc(status)}</span>`;
}

function orderCard(o) {
    const items = o.items.map(i => `
        <div class="order-item">
            <span>${esc(i.productName)} × ${i.quantity}</span>
            <span>$${parseFloat(i.subtotal).toFixed(2)}</span>
        </div>`).join('');

    const date = new Date(o.createdAt).toLocaleDateString('en-US', {
        year:'numeric', month:'short', day:'numeric'
    });

    return `
        <div class="order-card">
            <div class="order-header">
                <div>
                    <div class="order-id">#${esc(o.id.slice(0,8).toUpperCase())}</div>
                    <div style="font-size:0.78rem;color:var(--text-muted);margin-top:2px">${date}</div>
                </div>
                <div style="display:flex;align-items:center;gap:0.75rem">
                    ${statusBadge(o.status)}
                    <span class="order-total">$${parseFloat(o.totalPrice).toFixed(2)}</span>
                </div>
            </div>
            <div class="order-items">${items}</div>
        </div>`;
}

async function openOrderModal() {
    quantities = {};
    try {
        const res = await apiFetch('/api/products?size=100', { headers: authHeaders() });
        allProducts = res.content || res;
        const list = document.getElementById('product-select-list');

        if (!allProducts.length) {
            list.innerHTML = `<p style="color:var(--text-muted);font-size:0.85rem">No products available.</p>`;
        } else {
            list.innerHTML = allProducts.map(p => `
                <div class="product-select-item">
                    <div>
                        <div style="font-size:0.88rem;font-weight:600">${esc(p.name)}</div>
                        <div style="font-size:0.78rem;color:var(--text-muted)">$${parseFloat(p.price).toFixed(2)} · Stock: ${p.stock}</div>
                    </div>
                    <div class="qty-control">
                        <button class="qty-btn" onclick="changeQty('${esc(p.id)}', -1, ${p.stock}, ${p.price})">−</button>
                        <span class="qty-display" id="qty-${esc(p.id)}">0</span>
                        <button class="qty-btn" onclick="changeQty('${esc(p.id)}', 1, ${p.stock}, ${p.price})">+</button>
                    </div>
                </div>`).join('');
        }

        updateOrderTotal();
        document.getElementById('order-modal').classList.add('open');
    } catch(e) { toast(e.message, 'error'); }
}

function closeOrderModal() {
    document.getElementById('order-modal').classList.remove('open');
}

function changeQty(id, delta, maxStock, price) {
    const current = quantities[id] || 0;
    const next = Math.max(0, Math.min(maxStock, current + delta));
    quantities[id] = next;
    document.getElementById(`qty-${id}`).textContent = next;
    updateOrderTotal();
}

function updateOrderTotal() {
    let total = 0;
    allProducts.forEach(p => {
        total += (quantities[p.id] || 0) * parseFloat(p.price);
    });
    document.getElementById('order-total').textContent = '$' + total.toFixed(2);
}

async function submitOrder() {
    const items = Object.entries(quantities)
        .filter(([_, qty]) => qty > 0)
        .map(([productId, quantity]) => ({ productId, quantity }));

    if (!items.length) { toast('Select at least one product.', 'error'); return; }

    try {
        await apiFetch('/api/orders', {
            method: 'POST', headers: authHeaders(),
            body: JSON.stringify({ items })
        });
        closeOrderModal();
        toast('Order placed successfully! 🎉');
        loadOrders();
    } catch(e) { toast(e.message, 'error'); }
}

document.getElementById('order-modal').addEventListener('click', function(e) {
    if (e.target === this) closeOrderModal();
});