import os
import re
import time
from bs4 import BeautifulSoup
from deep_translator import GoogleTranslator

INPUT_FILE = r"app/src/main/res/values/strings.xml"
OUTPUT_DIR_BASE = r"app/src/main/res/values"

LANGS = ["hi", "gu", "mr", "kn", "ml", "ta", "te", "or", "bn"]

def translate_xml():
    with open(INPUT_FILE, 'r', encoding='utf-8') as f:
        xml_content = f.read()

    soup = BeautifulSoup(xml_content, 'xml')
    
    for lang in LANGS:
        print(f"Translating to {lang}...")
        out_dir = f"{OUTPUT_DIR_BASE}-{lang}"
        os.makedirs(out_dir, exist_ok=True)
        
        # Deep copy the soup for this language
        lang_soup = BeautifulSoup(xml_content, 'xml')
        lang_strings = lang_soup.find_all('string')
        lang_plurals = lang_soup.find_all('plurals')

        all_elements = []
        for s in lang_strings:
            if s.get('translatable') == 'false':
                continue
            all_elements.append(s)
            
        for p in lang_plurals:
            for item in p.find_all('item'):
                all_elements.append(item)

        target_lang = lang if lang != 'or' else 'or'
        translator = GoogleTranslator(source='auto', target=target_lang)

        # Batch translate
        batch_size = 50
        for i in range(0, len(all_elements), batch_size):
            batch = all_elements[i:i+batch_size]
            texts_to_translate = []
            
            for elem in batch:
                text = elem.text
                if not text:
                    text = " "
                texts_to_translate.append(text)
            
            try:
                translations = translator.translate_batch(texts_to_translate)
                
                for elem, translated in zip(batch, translations):
                    if translated:
                        # Fix up any messed up formatting arguments like % 1 $ s
                        fixed = re.sub(r'%\s*(\d+)\s*\$\s*([a-zA-Z])', r'%\1$\2', translated)
                        fixed = re.sub(r'%\s*([a-zA-Z])', r'%\1', fixed)
                        elem.string = fixed
            except Exception as e:
                print(f"Error translating batch: {e}")
                time.sleep(1)

        # We must also format the XML nicely
        with open(os.path.join(out_dir, 'strings.xml'), 'w', encoding='utf-8') as f:
            f.write(str(lang_soup))
        print(f"Saved {lang}.")

if __name__ == '__main__':
    translate_xml()
