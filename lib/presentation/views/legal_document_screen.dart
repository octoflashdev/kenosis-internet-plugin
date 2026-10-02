import 'package:flutter/material.dart';

import '../../legal/legal_document.dart';
import '../../legal/legal_document_repository.dart';

/// Shows a bundled legal document (Privacy Notice / Licenses) as readable
/// text. Thin view: loading is delegated to [LegalDocumentRepository].
class LegalDocumentScreen extends StatefulWidget {
  final LegalDocumentRepository legalRepository;
  final LegalDocument document;

  const LegalDocumentScreen({
    super.key,
    required this.legalRepository,
    required this.document,
  });

  @override
  State<LegalDocumentScreen> createState() => _LegalDocumentScreenState();
}

class _LegalDocumentScreenState extends State<LegalDocumentScreen> {
  late final Future<String> _textFuture;

  @override
  void initState() {
    super.initState();
    _textFuture = widget.legalRepository.load(widget.document);
  }

  @override
  Widget build(BuildContext context) {
    final isLicense = widget.document == LegalDocument.license;
    return Scaffold(
      appBar: AppBar(title: Text(widget.document.title)),
      body: FutureBuilder<String>(
        future: _textFuture,
        builder: (context, snapshot) {
          if (snapshot.connectionState == ConnectionState.waiting) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(child: Text('Could not load document: ${snapshot.error}'));
          }
          final text = snapshot.data ?? '';
          // The license is dense, pre-formatted legal boilerplate (Apache-2.0)
          // that relies on fixed-width alignment and centering. Render it
          // VERBATIM in a monospace font, not wrapped, inside a horizontal
          // scroller so the original formatting is preserved. The privacy
          // notice is short prose and renders as wrapped markdown.
          if (isLicense) {
            return SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Text(
                  text,
                  softWrap: false,
                  style: const TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 10.5,
                    height: 1.35,
                  ),
                ),
              ),
            );
          }
          return SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: _SimpleMarkdown(text: text),
          );
        },
      ),
    );
  }
}

/// Minimal block-level markdown renderer for the bundled PROSE legal doc
/// (Privacy Notice): supports `#`/`##` headings, `- ` bullets, `---`
/// horizontal rules, blank-line paragraphs, and inline `**bold**`. Consecutive
/// plain-text lines are JOINED into one paragraph (soft-wrap) — a hard wrap in
/// the .md source never breaks a rendered sentence. Deliberately small — this
/// is a short, controlled file. The license document is NOT rendered here; it
/// uses a preformatted monospace block (see [build]).
class _SimpleMarkdown extends StatelessWidget {
  final String text;
  const _SimpleMarkdown({required this.text});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context).textTheme;
    final blocks = <Widget>[];

    // Soft-wrap joining: consecutive plain-text lines form ONE paragraph. A
    // hard wrap inside a sentence (markdown source formatting) must not break
    // the rendered paragraph into fragments. Headings, bullets, rules and
    // blank lines end the current paragraph.
    final paragraph = StringBuffer();

    void flushParagraph() {
      if (paragraph.isEmpty) return;
      blocks.add(Padding(
        padding: const EdgeInsets.only(bottom: 4),
        child: _inline(paragraph.toString(), theme.bodyMedium!),
      ));
      paragraph.clear();
    }

    for (final rawLine in text.split('\n')) {
      final line = rawLine.trimRight();
      if (line.isEmpty) {
        flushParagraph();
        blocks.add(const SizedBox(height: 10));
      } else if (line.trim() == '---') {
        flushParagraph();
        blocks.add(const Padding(
          padding: EdgeInsets.symmetric(vertical: 8),
          child: Divider(height: 1),
        ));
      } else if (line.startsWith('## ')) {
        flushParagraph();
        blocks.add(Padding(
          padding: const EdgeInsets.only(top: 12, bottom: 4),
          child: _inline(line.substring(3), theme.titleMedium!),
        ));
      } else if (line.startsWith('# ')) {
        flushParagraph();
        blocks.add(Padding(
          padding: const EdgeInsets.only(bottom: 8),
          child: _inline(line.substring(2), theme.headlineSmall!),
        ));
      } else if (line.startsWith('- ')) {
        flushParagraph();
        blocks.add(Padding(
          padding: const EdgeInsets.only(left: 8, bottom: 4),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('•  '),
              Expanded(child: _inline(line.substring(2), theme.bodyMedium!)),
            ],
          ),
        ));
      } else {
        // Joined with a single space so the sentence reads continuously.
        if (paragraph.isNotEmpty) paragraph.write(' ');
        paragraph.write(line.trim());
      }
    }
    flushParagraph();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: blocks,
    );
  }

  /// Renders `**bold**` spans within a line.
  Widget _inline(String content, TextStyle style) {
    final spans = <TextSpan>[];
    final pattern = RegExp(r'\*\*(.+?)\*\*');
    final boldStyle = style.copyWith(fontWeight: FontWeight.bold);
    var lastEnd = 0;
    for (final m in pattern.allMatches(content)) {
      if (m.start > lastEnd) {
        spans.add(TextSpan(text: content.substring(lastEnd, m.start), style: style));
      }
      spans.add(TextSpan(text: m.group(1), style: boldStyle));
      lastEnd = m.end;
    }
    if (lastEnd < content.length) {
      spans.add(TextSpan(text: content.substring(lastEnd), style: style));
    }
    return RichText(text: TextSpan(children: spans));
  }
}
