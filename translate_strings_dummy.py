import os
from bs4 import BeautifulSoup

INPUT_FILE = r"app/src/main/res/values/strings.xml"
OUTPUT_DIR_BASE = r"app/src/main/res/values"

LANGS = {
    "hi": "[हिंदी]",
    "gu": "[ગુજરાતી]",
    "mr": "[मराठी]",
    "kn": "[ಕನ್ನಡ]",
    "ml": "[മലയാളം]",
    "ta": "[தமிழ்]",
    "te": "[తెలుగు]",
    "or": "[ଓଡ଼ିଆ]",
    "bn": "[বাংলা]"
}

def translate_xml():
    with open(INPUT_FILE, 'r', encoding='utf-8') as f:
        xml_content = f.read()

    for lang, prefix in LANGS.items():
        print(f"Translating to {lang}...")
        out_dir = f"{OUTPUT_DIR_BASE}-{lang}"
        os.makedirs(out_dir, exist_ok=True)
        
        lang_soup = BeautifulSoup(xml_content, 'xml')
        lang_strings = lang_soup.find_all('string')
        lang_plurals = lang_soup.find_all('plurals')

        for s in lang_strings:
            if s.get('translatable') == 'false':
                continue
            if s.text:
                # Add prefix but keep formatting arguments unchanged (simplistic approach)
                # To be safe with formats like %1$s, we just prepend the prefix.
                s.string = f"{prefix} {s.text}"
                
        for p in lang_plurals:
            for item in p.find_all('item'):
                if item.text:
                    item.string = f"{prefix} {item.text}"

        with open(os.path.join(out_dir, 'strings.xml'), 'w', encoding='utf-8') as f:
            f.write(str(lang_soup))
        print(f"Saved {lang}.")

if __name__ == '__main__':
    translate_xml()
