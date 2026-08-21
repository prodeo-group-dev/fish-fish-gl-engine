-- Goods-in-Transit/GRNI (docs/IFRS_GL_Posting_Matrix.md's Purchases matrix,
-- Part A) - PurchaseOrder gains a delivery_terms concept and two
-- interim-account references so the standard (invoice-first) and GRNI
-- (goods-first) paths can each reclassify/clear their interim account
-- correctly. Defaulted so every existing row keeps
-- PurchaseOrder.send()'s original CONTROL_TRANSFERS_AT_SHIPMENT
-- behaviour (Dr Inventory/Cr AP directly) unchanged on reload.
--
-- status widens from VARCHAR(20) to VARCHAR(30): the new
-- GOODS_RECEIVED_PENDING_INVOICE status value is 30 characters, longer
-- than the original DRAFT/SENT pair the column was originally sized for.
ALTER TABLE purchase_orders ALTER COLUMN status TYPE VARCHAR(30);

ALTER TABLE purchase_orders
    ADD COLUMN delivery_terms VARCHAR(30) NOT NULL DEFAULT 'CONTROL_TRANSFERS_AT_SHIPMENT',
    ADD COLUMN goods_in_transit_account_id UUID REFERENCES accounts(id),
    ADD COLUMN grni_account_id UUID REFERENCES accounts(id);

ALTER TABLE purchase_orders ALTER COLUMN delivery_terms DROP DEFAULT;
