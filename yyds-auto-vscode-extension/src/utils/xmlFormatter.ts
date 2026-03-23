/**
 * XML 格式化工具 — 移植自 XmlFormatter.java
 * 简单的 XML 美化格式化
 */

/**
 * 格式化 XML 字符串
 */
export function formatXml(xml: string): string {
    if (!xml || xml.trim().length === 0) {
        return xml;
    }

    let formatted = '';
    let indent = '';
    const indentUnit = '  ';

    // 简单的 XML 格式化
    const tokens = xml.replace(/>\s*</g, '>\n<').split('\n');

    for (const token of tokens) {
        const trimmed = token.trim();
        if (trimmed.length === 0) { continue; }

        if (trimmed.startsWith('</')) {
            // 闭合标签 — 减少缩进
            indent = indent.substring(indentUnit.length);
            formatted += indent + trimmed + '\n';
        } else if (trimmed.startsWith('<') && trimmed.endsWith('/>')) {
            // 自闭合标签
            formatted += indent + trimmed + '\n';
        } else if (trimmed.startsWith('<') && !trimmed.startsWith('<?') && !trimmed.startsWith('<!')) {
            // 开始标签
            formatted += indent + trimmed + '\n';
            if (!trimmed.includes('</')) {
                indent += indentUnit;
            }
        } else {
            formatted += indent + trimmed + '\n';
        }
    }

    return formatted;
}

/**
 * XML 转义为 HTML 安全文本
 */
export function escapeXml(xml: string): string {
    return xml
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}
