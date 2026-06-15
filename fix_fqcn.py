import os
import re

def process_file(filepath):
    with open(filepath, 'r') as f:
        content = f.read()

    # Find all FQCNs (ignoring package and import statements)
    # We look for something like com.sbshop.agent... followed by a capital letter class name
    # Regex breakdown:
    # (?<!package )(?<!import ) -> negative lookbehind (Python regex supports this for fixed length, but since it's variable we'll just check the line)
    
    lines = content.split('\n')
    new_lines = []
    imports_to_add = set()
    modified = False

    fqcn_pattern = re.compile(r'\b(com\.sbshop\.agent\.[a-zA-Z0-9_\.]+\.([A-Z][a-zA-Z0-9_]*))\b')

    for line in lines:
        if line.startswith('package ') or line.startswith('import '):
            new_lines.append(line)
            continue
        
        # Find all FQCNs in this line
        matches = fqcn_pattern.findall(line)
        if matches:
            modified = True
            for fqcn, class_name in matches:
                # Add to imports
                imports_to_add.add(fqcn)
                # Replace in line
                line = line.replace(fqcn, class_name)
        new_lines.append(line)

    if not modified:
        return

    # Now we need to add the imports
    # Find the last import statement or the package statement
    insert_idx = 0
    for i, line in enumerate(new_lines):
        if line.startswith('import '):
            insert_idx = i + 1
        elif line.startswith('package ') and insert_idx == 0:
            insert_idx = i + 1

    # Insert imports
    for imp in sorted(imports_to_add):
        # Only add if not already imported (maybe with wildcard, but let's just avoid exact duplicates)
        imp_stmt = f"import {imp};"
        if imp_stmt not in new_lines:
            new_lines.insert(insert_idx, imp_stmt)
            insert_idx += 1

    with open(filepath, 'w') as f:
        f.write('\n'.join(new_lines))

    print(f"Modified {filepath}")

for root, _, files in os.walk('backend'):
    for file in files:
        if file.endswith('.java'):
            process_file(os.path.join(root, file))

