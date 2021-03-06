/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bson.codecs.jsr310

import org.bson.codecs.configuration.CodecRegistry
import spock.lang.IgnoreIf
import spock.lang.Specification

class Jsr310CodecProviderSpecification extends Specification {

    @IgnoreIf({ javaVersion < 1.8 })
    def 'should provide a codec for all JSR-310 classes'() {
        given:
        def codecRegistry = Stub(CodecRegistry)
        def provider = new Jsr310CodecProvider()

        expect:
        provider.get(clazz, codecRegistry) != null

        where:
        clazz << [
            java.time.Instant,
            java.time.LocalDate,
            java.time.LocalDateTime,
            java.time.LocalTime,
        ]
    }

    @IgnoreIf({ javaVersion > 1.7 })
    def 'should not error when used on pre java 8'() {
        given:
        def codecRegistry = Stub(CodecRegistry)
        def provider = new Jsr310CodecProvider()

        expect:
        provider.get(Integer, codecRegistry) == null
    }
}
