import os

files_to_update = [
    "backend/core/src/main/java/com/sbshop/agent/core/domain/fee/FeePolicy.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/market/MarketCredential.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/product/Product.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/common/BaseEntity.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/product/vo/ProductSpec.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/order/Order.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/order/vo/ShippingData.java",
    "backend/core/src/main/java/com/sbshop/agent/core/domain/order/vo/CustomsData.java"
]

target_string = "@org.hibernate.annotations.JdbcTypeCode(java.sql.Types.VARCHAR)"
replacement_string = "@JdbcTypeCode(Types.VARCHAR)"

imports_to_add = [
    "import org.hibernate.annotations.JdbcTypeCode;",
    "import java.sql.Types;"
]

for filepath in files_to_update:
    if not os.path.exists(filepath):
        continue

    with open(filepath, 'r') as f:
        content = f.read()

    if target_string not in content:
        continue

    content = content.replace(target_string, replacement_string)

    lines = content.split('\n')
    
    # Add imports after the last import or package statement
    insert_idx = 0
    for i, line in enumerate(lines):
        if line.startswith('import '):
            insert_idx = i + 1
        elif line.startswith('package ') and insert_idx == 0:
            insert_idx = i + 1

    for imp in imports_to_add:
        if imp + ";" not in content and imp not in content:
            lines.insert(insert_idx, imp)
            insert_idx += 1

    with open(filepath, 'w') as f:
        f.write('\n'.join(lines))

    print(f"Updated {filepath}")
