function esc(s) {
    return String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

document.addEventListener('DOMContentLoaded', () => {
    if (!requireAuth()) return;
    updateNav();
    if (isAdmin()) document.getElementById('admin-section').style.display = 'block';
    loadProducts();
});

async function loadProducts() {
    const container = document.getElementById('products-container');
    try {
        const res = await apiFetch('/api/products?size=50', { headers: authHeaders() });
        const products = res.content || res;
        if (!products.length) {
            container.innerHTML = `
                <div class="empty-state">
                    <div class="empty-icon">📦</div>
                    <p>No products available yet.</p>
                </div>`;
            return;
        }
        container.innerHTML = `<div class="card-grid">${products.map(productCard).join('')}</div>`;
    } catch(e) {
        container.innerHTML = `<div class="empty-state"><p>Failed to load products: ${esc(e.message)}</p></div>`;
    }
}

function productCard(p) {
    const stockBadge = p.stock === 0
        ? `<span class="badge badge-empty">Out of stock</span>`
        : p.stock < 5
            ? `<span class="badge badge-low">Low: ${p.stock}</span>`
            : `<span class="badge badge-stock">In stock: ${p.stock}</span>`;

    const adminActions = isAdmin() ? `
        <div style="display:flex;gap:0.4rem;margin-top:0.75rem">
            <button class="btn btn-outline btn-sm" onclick="editStock('${esc(p.id)}', ${p.stock})">Edit Stock</button>
            <button class="btn btn-danger btn-sm" onclick="deleteProduct('${esc(p.id)}')">Delete</button>
        </div>` : '';

    return `
        <div class="card" id="card-${esc(p.id)}">
            <div class="card-name">${esc(p.name)}</div>
            <div class="card-desc">${esc(p.description || 'No description')}</div>
            <div class="card-footer">
                <span class="price">$${parseFloat(p.price).toFixed(2)}</span>
                ${stockBadge}
            </div>
            ${adminActions}
        </div>`;
}

async function createProduct() {
    const name  = document.getElementById('p-name').value.trim();
    const price = document.getElementById('p-price').value;
    const stock = document.getElementById('p-stock').value;
    const desc  = document.getElementById('p-desc').value.trim();

    if (!name || !price || stock === '') { toast('Please fill in all required fields.', 'error'); return; }

    try {
        await apiFetch('/api/products', {
            method: 'POST', headers: authHeaders(),
            body: JSON.stringify({ name, description: desc, price: parseFloat(price), stock: parseInt(stock) })
        });
        toast('Product created!');
        ['p-name','p-price','p-stock','p-desc'].forEach(id => document.getElementById(id).value = '');
        loadProducts();
    } catch(e) { toast(e.message, 'error'); }
}

async function editStock(id, current) {
    const qty = prompt(`Current stock: ${current}\nEnter new stock:`, current);
    if (qty === null || isNaN(parseInt(qty))) return;
    try {
        await apiFetch(`/api/products/${id}/stock`, {
            method: 'PATCH', headers: authHeaders(),
            body: JSON.stringify({ stock: parseInt(qty) })
        });
        toast('Stock updated!');
        loadProducts();
    } catch(e) { toast(e.message, 'error'); }
}

async function deleteProduct(id) {
    if (!confirm('Soft delete this product?')) return;
    try {
        await apiFetch(`/api/products/${id}`, { method: 'DELETE', headers: authHeaders() });
        toast('Product deleted.');
        document.getElementById(`card-${id}`)?.remove();
    } catch(e) { toast(e.message, 'error'); }
}