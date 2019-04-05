/****************************************************************************
**
** Copyright (C) 2019 The Qt Company Ltd.
** Contact: https://www.qt.io/licensing/
**
** This file is part of the plugins of the Qt Toolkit.
**
** $QT_BEGIN_LICENSE:LGPL$
** Commercial License Usage
** Licensees holding valid commercial Qt licenses may use this file in
** accordance with the commercial license agreement provided with the
** Software or, alternatively, in accordance with the terms contained in
** a written agreement between you and The Qt Company. For licensing terms
** and conditions see https://www.qt.io/terms-conditions. For further
** information use the contact form at https://www.qt.io/contact-us.
**
** GNU Lesser General Public License Usage
** Alternatively, this file may be used under the terms of the GNU Lesser
** General Public License version 3 as published by the Free Software
** Foundation and appearing in the file LICENSE.LGPL3 included in the
** packaging of this file. Please review the following information to
** ensure the GNU Lesser General Public License version 3 requirements
** will be met: https://www.gnu.org/licenses/lgpl-3.0.html.
**
** GNU General Public License Usage
** Alternatively, this file may be used under the terms of the GNU
** General Public License version 2.0 or (at your option) the GNU General
** Public license version 3 or any later version approved by the KDE Free
** Qt Foundation. The licenses are as published by the Free Software
** Foundation and appearing in the file LICENSE.GPL2 and LICENSE.GPL3
** included in the packaging of this file. Please review the following
** information to ensure the GNU General Public License requirements will
** be met: https://www.gnu.org/licenses/gpl-2.0.html and
** https://www.gnu.org/licenses/gpl-3.0.html.
**
** $QT_END_LICENSE$
**
****************************************************************************/

#include "qwindowsdirectwritefontdatabase_p.h"

#include <QtCore/qstringbuilder.h>
#include <QtCore/qvarlengtharray.h>
#include <QtCore/private/qsystemlibrary_p.h>

#if defined(QT_USE_DIRECTWRITE2)
#  include <dwrite_2.h>
#else
#  include <dwrite.h>
#endif
#include <d2d1.h>

QT_BEGIN_NAMESPACE

Q_LOGGING_CATEGORY(lcQpaFonts, "qt.qpa.fonts")

typedef HRESULT (WINAPI *DWriteCreateFactoryType)(DWRITE_FACTORY_TYPE, const IID &, IUnknown **);

// ### Qt 6: Link directly to dwrite instead
static inline DWriteCreateFactoryType resolveDWriteCreateFactory()
{
    QSystemLibrary library(QStringLiteral("dwrite"));
    QFunctionPointer result = library.resolve("DWriteCreateFactory");
    if (Q_UNLIKELY(!result)) {
        qWarning("Unable to load dwrite.dll");
        return nullptr;
    }
    return reinterpret_cast<DWriteCreateFactoryType>(result);
}

QWindowsDirectWriteFontDatabase::QWindowsDirectWriteFontDatabase()
{
    createDirectWriteFactory(&m_directWriteFactory);
}

QWindowsDirectWriteFontDatabase::~QWindowsDirectWriteFontDatabase()
{
    if (m_directWriteFactory != nullptr)
        m_directWriteFactory->Release();
}

QString QWindowsDirectWriteFontDatabase::localeString(IDWriteLocalizedStrings *names,
                                                      wchar_t localeName[])
{
    uint index;
    BOOL exists;
    if (SUCCEEDED(names->FindLocaleName(localeName, &index, &exists)) && exists) {
        uint length;
        if (SUCCEEDED(names->GetStringLength(index, &length)) && length > 0) {
            QVarLengthArray<wchar_t> buffer(int(length) + 1);
            if (SUCCEEDED(names->GetString(index, buffer.data(), length + 1)))
                return QString::fromWCharArray(buffer.data());
        }
    }

    return QString();
}

void QWindowsDirectWriteFontDatabase::populateFamily(const QString &familyName)
{
    Q_UNUSED(familyName);
}

QFontEngine *QWindowsDirectWriteFontDatabase::fontEngine(const QFontDef &fontDef, void *handle)
{
    Q_UNUSED(fontDef);
    Q_UNUSED(handle);
    return nullptr;
}

QFontEngine *QWindowsDirectWriteFontDatabase::fontEngine(const QByteArray &fontData, qreal pixelSize, QFont::HintingPreference hintingPreference)
{
    Q_UNUSED(fontData);
    Q_UNUSED(pixelSize);
    Q_UNUSED(hintingPreference);

    return nullptr;
}

QStringList QWindowsDirectWriteFontDatabase::fallbacksForFamily(const QString &family, QFont::Style style, QFont::StyleHint styleHint, QChar::Script script) const
{
    Q_UNUSED(family);
    Q_UNUSED(style);
    Q_UNUSED(styleHint);
    Q_UNUSED(script);

    return QStringList();
}

QStringList QWindowsDirectWriteFontDatabase::addApplicationFont(const QByteArray &fontData, const QString &fileName)
{
    Q_UNUSED(fontData);
    Q_UNUSED(fileName);

    return QStringList();
}

void QWindowsDirectWriteFontDatabase::releaseHandle(void *handle)
{
    Q_UNUSED(handle);
}

QString QWindowsDirectWriteFontDatabase::fontDir() const
{
    return QString();
}

QFont QWindowsDirectWriteFontDatabase::defaultFont() const
{
    return QFont();
}

bool QWindowsDirectWriteFontDatabase::fontsAlwaysScalable() const
{
    return true;
}

bool QWindowsDirectWriteFontDatabase::isPrivateFontFamily(const QString &family) const
{
    return false;
}

void QWindowsDirectWriteFontDatabase::populateFontDatabase()
{
    if (m_directWriteFactory != nullptr)
        return;

    // removeApplicationFonts();

    wchar_t defaultLocale[LOCALE_NAME_MAX_LENGTH];
    bool hasDefaultLocale = GetUserDefaultLocaleName(defaultLocale, LOCALE_NAME_MAX_LENGTH) != 0;
    wchar_t englishLocale[] = L"en-us";

    IDWriteFontCollection *fontCollection;
    if (SUCCEEDED(m_directWriteFactory->GetSystemFontCollection(&fontCollection))) {
        for (uint i = 0; i < fontCollection->GetFontFamilyCount(); ++i) {
            IDWriteFontFamily *fontFamily;
            if (SUCCEEDED(fontCollection->GetFontFamily(i, &fontFamily))) {
                QString defaultLocaleName;
                QString englishLocaleName;

                IDWriteLocalizedStrings *names;
                if (SUCCEEDED(fontFamily->GetFamilyNames(&names))) {
                    if (hasDefaultLocale)
                        defaultLocaleName = localeString(names, defaultLocale);

                    englishLocaleName = localeString(names, englishLocale);
                }

                qCDebug(lcQpaFonts) << "Registering font, english name = " << englishLocaleName << ", name in current locale = " << defaultLocaleName;
                if (!defaultLocaleName.isEmpty())
                    registerFontFamily(defaultLocaleName);

                if (!englishLocaleName.isEmpty() && englishLocaleName != defaultLocaleName)
                    registerFontFamily(englishLocaleName);

                // ### For compatibility, should we look for any subfamily of the font which is not
                // bold, italic, bold italic or regular and register this as a font family as well?
                // Or at least as an alias? Should measure how much time it takes to do the matching
                // for all fonts.
                /*IDWriteFontList *matchingFonts;
                if (SUCCEEDED(fontFamily->GetMatchingFonts(DWRITE_FONT_WEIGHT_REGULAR,
                                                           DWRITE_FONT_STRETCH_NORMAL,
                                                           DWRITE_FONT_STYLE_NORMAL,
                                                           &matchingFonts))) {
                    for (uint j = 0; j < matchingFonts->GetFontCount(); ++j) {
                        IDWriteFont *font;
                        if (SUCCEEDED(matchingFonts->GetFont(j, &font)))
                            populateFont(englishLocaleName, defaultLocaleName, font);
                    }
                }*/
            }
        }
    }
}

void QWindowsDirectWriteFontDatabase::createDirectWriteFactory(IDWriteFactory **factory)
{
    *factory = nullptr;

    static const DWriteCreateFactoryType dWriteCreateFactory = resolveDWriteCreateFactory();
    if (!dWriteCreateFactory)
        return;

    IUnknown *result = nullptr;
#if defined(QT_USE_DIRECTWRITE2)
    dWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory2), &result);
#endif

    if (result == nullptr) {
        if (FAILED(dWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory), &result))) {
            qErrnoWarning("DWriteCreateFactory failed");
            return;
        }
    }

    *factory = static_cast<IDWriteFactory *>(result);
}

QT_END_NAMESPACE
