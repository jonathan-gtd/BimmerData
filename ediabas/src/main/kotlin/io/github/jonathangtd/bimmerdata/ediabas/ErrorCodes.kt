/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD
 * Kotlin port of EdiabasLib (https://github.com/uholeschak/ediabaslib, Ulrich Holeschak, GPL-3.0),
 * file EdiabasLib/EdiabasNet.cs.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version. See LICENSE.
 */
package io.github.jonathangtd.bimmerdata.ediabas

/**
 * EDIABAS error code (EdiabasNet.cs: enum ErrorCodes : uint). Modelled as a value class because
 * the C# enum has aliases (EDIABAS_xxx_LAST) and opcodes cast arbitrary numbers to it (generr).
 */
@JvmInline
value class ErrorCodes(val code: Int) : Comparable<ErrorCodes> {
    override fun compareTo(other: ErrorCodes): Int = code.compareTo(other.code)

    /** Same text as the C# enum ToString(): the constant name, or the number if unnamed. */
    override fun toString(): String = NAMES[code] ?: code.toString()

    companion object {
        val EDIABAS_ERR_NONE = ErrorCodes(0)
        val EDIABAS_IFH_0000 = ErrorCodes(10)
        val EDIABAS_IFH_0001 = ErrorCodes(11)
        val EDIABAS_IFH_0002 = ErrorCodes(12)
        val EDIABAS_IFH_0003 = ErrorCodes(13)
        val EDIABAS_IFH_0004 = ErrorCodes(14)
        val EDIABAS_IFH_0005 = ErrorCodes(15)
        val EDIABAS_IFH_0006 = ErrorCodes(16)
        val EDIABAS_IFH_0007 = ErrorCodes(17)
        val EDIABAS_IFH_0008 = ErrorCodes(18)
        val EDIABAS_IFH_0009 = ErrorCodes(19)
        val EDIABAS_IFH_0010 = ErrorCodes(20)
        val EDIABAS_IFH_0011 = ErrorCodes(21)
        val EDIABAS_IFH_0012 = ErrorCodes(22)
        val EDIABAS_IFH_0013 = ErrorCodes(23)
        val EDIABAS_IFH_0014 = ErrorCodes(24)
        val EDIABAS_IFH_0015 = ErrorCodes(25)
        val EDIABAS_IFH_0016 = ErrorCodes(26)
        val EDIABAS_IFH_0017 = ErrorCodes(27)
        val EDIABAS_IFH_0018 = ErrorCodes(28)
        val EDIABAS_IFH_0019 = ErrorCodes(29)
        val EDIABAS_IFH_0020 = ErrorCodes(30)
        val EDIABAS_IFH_0021 = ErrorCodes(31)
        val EDIABAS_IFH_0022 = ErrorCodes(32)
        val EDIABAS_IFH_0023 = ErrorCodes(33)
        val EDIABAS_IFH_0024 = ErrorCodes(34)
        val EDIABAS_IFH_0025 = ErrorCodes(35)
        val EDIABAS_IFH_0026 = ErrorCodes(36)
        val EDIABAS_IFH_0027 = ErrorCodes(37)
        val EDIABAS_IFH_0028 = ErrorCodes(38)
        val EDIABAS_IFH_0029 = ErrorCodes(39)
        val EDIABAS_IFH_0030 = ErrorCodes(40)
        val EDIABAS_IFH_0031 = ErrorCodes(41)
        val EDIABAS_IFH_0032 = ErrorCodes(42)
        val EDIABAS_IFH_0033 = ErrorCodes(43)
        val EDIABAS_IFH_0034 = ErrorCodes(44)
        val EDIABAS_IFH_0035 = ErrorCodes(45)
        val EDIABAS_IFH_0036 = ErrorCodes(46)
        val EDIABAS_IFH_0037 = ErrorCodes(47)
        val EDIABAS_IFH_0038 = ErrorCodes(48)
        val EDIABAS_IFH_0039 = ErrorCodes(49)
        val EDIABAS_IFH_0040 = ErrorCodes(50)
        val EDIABAS_IFH_0041 = ErrorCodes(51)
        val EDIABAS_IFH_0042 = ErrorCodes(52)
        val EDIABAS_IFH_0043 = ErrorCodes(53)
        val EDIABAS_IFH_0044 = ErrorCodes(54)
        val EDIABAS_IFH_0045 = ErrorCodes(55)
        val EDIABAS_IFH_0046 = ErrorCodes(56)
        val EDIABAS_IFH_0047 = ErrorCodes(57)
        val EDIABAS_IFH_0048 = ErrorCodes(58)
        val EDIABAS_IFH_0049 = ErrorCodes(59)
        val EDIABAS_IFH_LAST = ErrorCodes(59)
        val EDIABAS_BIP_0000 = ErrorCodes(60)
        val EDIABAS_BIP_0001 = ErrorCodes(61)
        val EDIABAS_BIP_0002 = ErrorCodes(62)
        val EDIABAS_BIP_0003 = ErrorCodes(63)
        val EDIABAS_BIP_0004 = ErrorCodes(64)
        val EDIABAS_BIP_0005 = ErrorCodes(65)
        val EDIABAS_BIP_0006 = ErrorCodes(66)
        val EDIABAS_BIP_0007 = ErrorCodes(67)
        val EDIABAS_BIP_0008 = ErrorCodes(68)
        val EDIABAS_BIP_0009 = ErrorCodes(69)
        val EDIABAS_BIP_0010 = ErrorCodes(70)
        val EDIABAS_BIP_0011 = ErrorCodes(71)
        val EDIABAS_BIP_0012 = ErrorCodes(72)
        val EDIABAS_BIP_0013 = ErrorCodes(73)
        val EDIABAS_BIP_0014 = ErrorCodes(74)
        val EDIABAS_BIP_0015 = ErrorCodes(75)
        val EDIABAS_BIP_0016 = ErrorCodes(76)
        val EDIABAS_BIP_0017 = ErrorCodes(77)
        val EDIABAS_BIP_0018 = ErrorCodes(78)
        val EDIABAS_BIP_0019 = ErrorCodes(79)
        val EDIABAS_BIP_0020 = ErrorCodes(80)
        val EDIABAS_BIP_0021 = ErrorCodes(81)
        val EDIABAS_BIP_0022 = ErrorCodes(82)
        val EDIABAS_BIP_0023 = ErrorCodes(83)
        val EDIABAS_BIP_0024 = ErrorCodes(84)
        val EDIABAS_BIP_0025 = ErrorCodes(85)
        val EDIABAS_BIP_0026 = ErrorCodes(86)
        val EDIABAS_BIP_0027 = ErrorCodes(87)
        val EDIABAS_BIP_0028 = ErrorCodes(88)
        val EDIABAS_BIP_0029 = ErrorCodes(89)
        val EDIABAS_BIP_LAST = ErrorCodes(89)
        val EDIABAS_SYS_0000 = ErrorCodes(90)
        val EDIABAS_SYS_0001 = ErrorCodes(91)
        val EDIABAS_SYS_0002 = ErrorCodes(92)
        val EDIABAS_SYS_0003 = ErrorCodes(93)
        val EDIABAS_SYS_0004 = ErrorCodes(94)
        val EDIABAS_SYS_0005 = ErrorCodes(95)
        val EDIABAS_SYS_0006 = ErrorCodes(96)
        val EDIABAS_SYS_0007 = ErrorCodes(97)
        val EDIABAS_SYS_0008 = ErrorCodes(98)
        val EDIABAS_SYS_0009 = ErrorCodes(99)
        val EDIABAS_SYS_0010 = ErrorCodes(100)
        val EDIABAS_SYS_0011 = ErrorCodes(101)
        val EDIABAS_SYS_0012 = ErrorCodes(102)
        val EDIABAS_SYS_0013 = ErrorCodes(103)
        val EDIABAS_SYS_0014 = ErrorCodes(104)
        val EDIABAS_SYS_0015 = ErrorCodes(105)
        val EDIABAS_SYS_0016 = ErrorCodes(106)
        val EDIABAS_SYS_0017 = ErrorCodes(107)
        val EDIABAS_SYS_0018 = ErrorCodes(108)
        val EDIABAS_SYS_0019 = ErrorCodes(109)
        val EDIABAS_SYS_0020 = ErrorCodes(110)
        val EDIABAS_SYS_0021 = ErrorCodes(111)
        val EDIABAS_SYS_0022 = ErrorCodes(112)
        val EDIABAS_SYS_0023 = ErrorCodes(113)
        val EDIABAS_SYS_0024 = ErrorCodes(114)
        val EDIABAS_SYS_0025 = ErrorCodes(115)
        val EDIABAS_SYS_0026 = ErrorCodes(116)
        val EDIABAS_SYS_0027 = ErrorCodes(117)
        val EDIABAS_SYS_0028 = ErrorCodes(118)
        val EDIABAS_SYS_0029 = ErrorCodes(119)
        val EDIABAS_SYS_LAST = ErrorCodes(119)
        val EDIABAS_API_0000 = ErrorCodes(120)
        val EDIABAS_API_0001 = ErrorCodes(121)
        val EDIABAS_API_0002 = ErrorCodes(122)
        val EDIABAS_API_0003 = ErrorCodes(123)
        val EDIABAS_API_0004 = ErrorCodes(124)
        val EDIABAS_API_0005 = ErrorCodes(125)
        val EDIABAS_API_0006 = ErrorCodes(126)
        val EDIABAS_API_0007 = ErrorCodes(127)
        val EDIABAS_API_0008 = ErrorCodes(128)
        val EDIABAS_API_0009 = ErrorCodes(129)
        val EDIABAS_API_0010 = ErrorCodes(130)
        val EDIABAS_API_0011 = ErrorCodes(131)
        val EDIABAS_API_0012 = ErrorCodes(132)
        val EDIABAS_API_0013 = ErrorCodes(133)
        val EDIABAS_API_0014 = ErrorCodes(134)
        val EDIABAS_API_0015 = ErrorCodes(135)
        val EDIABAS_API_0016 = ErrorCodes(136)
        val EDIABAS_API_0017 = ErrorCodes(137)
        val EDIABAS_API_0018 = ErrorCodes(138)
        val EDIABAS_API_0019 = ErrorCodes(139)
        val EDIABAS_API_0020 = ErrorCodes(140)
        val EDIABAS_API_0021 = ErrorCodes(141)
        val EDIABAS_API_0022 = ErrorCodes(142)
        val EDIABAS_API_0023 = ErrorCodes(143)
        val EDIABAS_API_0024 = ErrorCodes(144)
        val EDIABAS_API_0025 = ErrorCodes(145)
        val EDIABAS_API_0026 = ErrorCodes(146)
        val EDIABAS_API_0027 = ErrorCodes(147)
        val EDIABAS_API_0028 = ErrorCodes(148)
        val EDIABAS_API_0029 = ErrorCodes(149)
        val EDIABAS_API_LAST = ErrorCodes(149)
        val EDIABAS_NET_0000 = ErrorCodes(150)
        val EDIABAS_NET_0001 = ErrorCodes(151)
        val EDIABAS_NET_0002 = ErrorCodes(152)
        val EDIABAS_NET_0003 = ErrorCodes(153)
        val EDIABAS_NET_0004 = ErrorCodes(154)
        val EDIABAS_NET_0005 = ErrorCodes(155)
        val EDIABAS_NET_0006 = ErrorCodes(156)
        val EDIABAS_NET_0007 = ErrorCodes(157)
        val EDIABAS_NET_0008 = ErrorCodes(158)
        val EDIABAS_NET_0009 = ErrorCodes(159)
        val EDIABAS_NET_0010 = ErrorCodes(160)
        val EDIABAS_NET_0011 = ErrorCodes(161)
        val EDIABAS_NET_0012 = ErrorCodes(162)
        val EDIABAS_NET_0013 = ErrorCodes(163)
        val EDIABAS_NET_0014 = ErrorCodes(164)
        val EDIABAS_NET_0015 = ErrorCodes(165)
        val EDIABAS_NET_0016 = ErrorCodes(166)
        val EDIABAS_NET_0017 = ErrorCodes(167)
        val EDIABAS_NET_0018 = ErrorCodes(168)
        val EDIABAS_NET_0019 = ErrorCodes(169)
        val EDIABAS_NET_0020 = ErrorCodes(170)
        val EDIABAS_NET_0021 = ErrorCodes(171)
        val EDIABAS_NET_0022 = ErrorCodes(172)
        val EDIABAS_NET_0023 = ErrorCodes(173)
        val EDIABAS_NET_0024 = ErrorCodes(174)
        val EDIABAS_NET_0025 = ErrorCodes(175)
        val EDIABAS_NET_0026 = ErrorCodes(176)
        val EDIABAS_NET_0027 = ErrorCodes(177)
        val EDIABAS_NET_0028 = ErrorCodes(178)
        val EDIABAS_NET_0029 = ErrorCodes(179)
        val EDIABAS_NET_0030 = ErrorCodes(180)
        val EDIABAS_NET_0031 = ErrorCodes(181)
        val EDIABAS_NET_0032 = ErrorCodes(182)
        val EDIABAS_NET_0033 = ErrorCodes(183)
        val EDIABAS_NET_0034 = ErrorCodes(184)
        val EDIABAS_NET_0035 = ErrorCodes(185)
        val EDIABAS_NET_0036 = ErrorCodes(186)
        val EDIABAS_NET_0037 = ErrorCodes(187)
        val EDIABAS_NET_0038 = ErrorCodes(188)
        val EDIABAS_NET_0039 = ErrorCodes(189)
        val EDIABAS_NET_0040 = ErrorCodes(190)
        val EDIABAS_NET_0041 = ErrorCodes(191)
        val EDIABAS_NET_0042 = ErrorCodes(192)
        val EDIABAS_NET_0043 = ErrorCodes(193)
        val EDIABAS_NET_0044 = ErrorCodes(194)
        val EDIABAS_NET_0045 = ErrorCodes(195)
        val EDIABAS_NET_0046 = ErrorCodes(196)
        val EDIABAS_NET_0047 = ErrorCodes(197)
        val EDIABAS_NET_0048 = ErrorCodes(198)
        val EDIABAS_NET_0049 = ErrorCodes(199)
        val EDIABAS_NET_LAST = ErrorCodes(199)
        val EDIABAS_IFH_0050 = ErrorCodes(200)
        val EDIABAS_IFH_0051 = ErrorCodes(201)
        val EDIABAS_IFH_0052 = ErrorCodes(202)
        val EDIABAS_IFH_0053 = ErrorCodes(203)
        val EDIABAS_IFH_0054 = ErrorCodes(204)
        val EDIABAS_IFH_0055 = ErrorCodes(205)
        val EDIABAS_IFH_0056 = ErrorCodes(206)
        val EDIABAS_IFH_0057 = ErrorCodes(207)
        val EDIABAS_IFH_0058 = ErrorCodes(208)
        val EDIABAS_IFH_0059 = ErrorCodes(209)
        val EDIABAS_IFH_0060 = ErrorCodes(210)
        val EDIABAS_IFH_0061 = ErrorCodes(211)
        val EDIABAS_IFH_0062 = ErrorCodes(212)
        val EDIABAS_IFH_0063 = ErrorCodes(213)
        val EDIABAS_IFH_0064 = ErrorCodes(214)
        val EDIABAS_IFH_0065 = ErrorCodes(215)
        val EDIABAS_IFH_0066 = ErrorCodes(216)
        val EDIABAS_IFH_0067 = ErrorCodes(217)
        val EDIABAS_IFH_0068 = ErrorCodes(218)
        val EDIABAS_IFH_0069 = ErrorCodes(219)
        val EDIABAS_IFH_0070 = ErrorCodes(220)
        val EDIABAS_IFH_0071 = ErrorCodes(221)
        val EDIABAS_IFH_0072 = ErrorCodes(222)
        val EDIABAS_IFH_0073 = ErrorCodes(223)
        val EDIABAS_IFH_0074 = ErrorCodes(224)
        val EDIABAS_IFH_0075 = ErrorCodes(225)
        val EDIABAS_IFH_0076 = ErrorCodes(226)
        val EDIABAS_IFH_0077 = ErrorCodes(227)
        val EDIABAS_IFH_0078 = ErrorCodes(228)
        val EDIABAS_IFH_0079 = ErrorCodes(229)
        val EDIABAS_IFH_0080 = ErrorCodes(230)
        val EDIABAS_IFH_0081 = ErrorCodes(231)
        val EDIABAS_IFH_0082 = ErrorCodes(232)
        val EDIABAS_IFH_0083 = ErrorCodes(233)
        val EDIABAS_IFH_0084 = ErrorCodes(234)
        val EDIABAS_IFH_0085 = ErrorCodes(235)
        val EDIABAS_IFH_0086 = ErrorCodes(236)
        val EDIABAS_IFH_0087 = ErrorCodes(237)
        val EDIABAS_IFH_0088 = ErrorCodes(238)
        val EDIABAS_IFH_0089 = ErrorCodes(239)
        val EDIABAS_IFH_0090 = ErrorCodes(240)
        val EDIABAS_IFH_0091 = ErrorCodes(241)
        val EDIABAS_IFH_0092 = ErrorCodes(242)
        val EDIABAS_IFH_0093 = ErrorCodes(243)
        val EDIABAS_IFH_0094 = ErrorCodes(244)
        val EDIABAS_IFH_0095 = ErrorCodes(245)
        val EDIABAS_IFH_0096 = ErrorCodes(246)
        val EDIABAS_IFH_0097 = ErrorCodes(247)
        val EDIABAS_IFH_0098 = ErrorCodes(248)
        val EDIABAS_IFH_0099 = ErrorCodes(249)
        val EDIABAS_IFH_LAST2 = ErrorCodes(249)
        val EDIABAS_RUN_0000 = ErrorCodes(250)
        val EDIABAS_RUN_0001 = ErrorCodes(251)
        val EDIABAS_RUN_0002 = ErrorCodes(252)
        val EDIABAS_RUN_0003 = ErrorCodes(253)
        val EDIABAS_RUN_0004 = ErrorCodes(254)
        val EDIABAS_RUN_0005 = ErrorCodes(255)
        val EDIABAS_RUN_0006 = ErrorCodes(256)
        val EDIABAS_RUN_0007 = ErrorCodes(257)
        val EDIABAS_RUN_0008 = ErrorCodes(258)
        val EDIABAS_RUN_0009 = ErrorCodes(259)
        val EDIABAS_RUN_0010 = ErrorCodes(260)
        val EDIABAS_RUN_0011 = ErrorCodes(261)
        val EDIABAS_RUN_0012 = ErrorCodes(262)
        val EDIABAS_RUN_0013 = ErrorCodes(263)
        val EDIABAS_RUN_0014 = ErrorCodes(264)
        val EDIABAS_RUN_0015 = ErrorCodes(265)
        val EDIABAS_RUN_0016 = ErrorCodes(266)
        val EDIABAS_RUN_0017 = ErrorCodes(267)
        val EDIABAS_RUN_0018 = ErrorCodes(268)
        val EDIABAS_RUN_0019 = ErrorCodes(269)
        val EDIABAS_RUN_0020 = ErrorCodes(270)
        val EDIABAS_RUN_0021 = ErrorCodes(271)
        val EDIABAS_RUN_0022 = ErrorCodes(272)
        val EDIABAS_RUN_0023 = ErrorCodes(273)
        val EDIABAS_RUN_0024 = ErrorCodes(274)
        val EDIABAS_RUN_0025 = ErrorCodes(275)
        val EDIABAS_RUN_0026 = ErrorCodes(276)
        val EDIABAS_RUN_0027 = ErrorCodes(277)
        val EDIABAS_RUN_0028 = ErrorCodes(278)
        val EDIABAS_RUN_0029 = ErrorCodes(279)
        val EDIABAS_RUN_0030 = ErrorCodes(280)
        val EDIABAS_RUN_0031 = ErrorCodes(281)
        val EDIABAS_RUN_0032 = ErrorCodes(282)
        val EDIABAS_RUN_0033 = ErrorCodes(283)
        val EDIABAS_RUN_0034 = ErrorCodes(284)
        val EDIABAS_RUN_0035 = ErrorCodes(285)
        val EDIABAS_RUN_0036 = ErrorCodes(286)
        val EDIABAS_RUN_0037 = ErrorCodes(287)
        val EDIABAS_RUN_0038 = ErrorCodes(288)
        val EDIABAS_RUN_0039 = ErrorCodes(289)
        val EDIABAS_RUN_0040 = ErrorCodes(290)
        val EDIABAS_RUN_0041 = ErrorCodes(291)
        val EDIABAS_RUN_0042 = ErrorCodes(292)
        val EDIABAS_RUN_0043 = ErrorCodes(293)
        val EDIABAS_RUN_0044 = ErrorCodes(294)
        val EDIABAS_RUN_0045 = ErrorCodes(295)
        val EDIABAS_RUN_0046 = ErrorCodes(296)
        val EDIABAS_RUN_0047 = ErrorCodes(297)
        val EDIABAS_RUN_0048 = ErrorCodes(298)
        val EDIABAS_RUN_0049 = ErrorCodes(299)
        val EDIABAS_RUN_0050 = ErrorCodes(300)
        val EDIABAS_RUN_0051 = ErrorCodes(301)
        val EDIABAS_RUN_0052 = ErrorCodes(302)
        val EDIABAS_RUN_0053 = ErrorCodes(303)
        val EDIABAS_RUN_0054 = ErrorCodes(304)
        val EDIABAS_RUN_0055 = ErrorCodes(305)
        val EDIABAS_RUN_0056 = ErrorCodes(306)
        val EDIABAS_RUN_0057 = ErrorCodes(307)
        val EDIABAS_RUN_0058 = ErrorCodes(308)
        val EDIABAS_RUN_0059 = ErrorCodes(309)
        val EDIABAS_RUN_0060 = ErrorCodes(310)
        val EDIABAS_RUN_0061 = ErrorCodes(311)
        val EDIABAS_RUN_0062 = ErrorCodes(312)
        val EDIABAS_RUN_0063 = ErrorCodes(313)
        val EDIABAS_RUN_0064 = ErrorCodes(314)
        val EDIABAS_RUN_0065 = ErrorCodes(315)
        val EDIABAS_RUN_0066 = ErrorCodes(316)
        val EDIABAS_RUN_0067 = ErrorCodes(317)
        val EDIABAS_RUN_0068 = ErrorCodes(318)
        val EDIABAS_RUN_0069 = ErrorCodes(319)
        val EDIABAS_RUN_0070 = ErrorCodes(320)
        val EDIABAS_RUN_0071 = ErrorCodes(321)
        val EDIABAS_RUN_0072 = ErrorCodes(322)
        val EDIABAS_RUN_0073 = ErrorCodes(323)
        val EDIABAS_RUN_0074 = ErrorCodes(324)
        val EDIABAS_RUN_0075 = ErrorCodes(325)
        val EDIABAS_RUN_0076 = ErrorCodes(326)
        val EDIABAS_RUN_0077 = ErrorCodes(327)
        val EDIABAS_RUN_0078 = ErrorCodes(328)
        val EDIABAS_RUN_0079 = ErrorCodes(329)
        val EDIABAS_RUN_0080 = ErrorCodes(330)
        val EDIABAS_RUN_0081 = ErrorCodes(331)
        val EDIABAS_RUN_0082 = ErrorCodes(332)
        val EDIABAS_RUN_0083 = ErrorCodes(333)
        val EDIABAS_RUN_0084 = ErrorCodes(334)
        val EDIABAS_RUN_0085 = ErrorCodes(335)
        val EDIABAS_RUN_0086 = ErrorCodes(336)
        val EDIABAS_RUN_0087 = ErrorCodes(337)
        val EDIABAS_RUN_0088 = ErrorCodes(338)
        val EDIABAS_RUN_0089 = ErrorCodes(339)
        val EDIABAS_RUN_0090 = ErrorCodes(340)
        val EDIABAS_RUN_0091 = ErrorCodes(341)
        val EDIABAS_RUN_0092 = ErrorCodes(342)
        val EDIABAS_RUN_0093 = ErrorCodes(343)
        val EDIABAS_RUN_0094 = ErrorCodes(344)
        val EDIABAS_RUN_0095 = ErrorCodes(345)
        val EDIABAS_RUN_0096 = ErrorCodes(346)
        val EDIABAS_RUN_0097 = ErrorCodes(347)
        val EDIABAS_RUN_0098 = ErrorCodes(348)
        val EDIABAS_RUN_0099 = ErrorCodes(349)
        val EDIABAS_RUN_LAST = ErrorCodes(349)
        val EDIABAS_SEC_0000 = ErrorCodes(350)
        val EDIABAS_SEC_0001 = ErrorCodes(351)
        val EDIABAS_SEC_0002 = ErrorCodes(352)
        val EDIABAS_SEC_0003 = ErrorCodes(353)
        val EDIABAS_SEC_0004 = ErrorCodes(354)
        val EDIABAS_SEC_0005 = ErrorCodes(355)
        val EDIABAS_SEC_0006 = ErrorCodes(356)
        val EDIABAS_SEC_0007 = ErrorCodes(357)
        val EDIABAS_SEC_0008 = ErrorCodes(358)
        val EDIABAS_SEC_0009 = ErrorCodes(359)
        val EDIABAS_SEC_0010 = ErrorCodes(360)
        val EDIABAS_SEC_0011 = ErrorCodes(361)
        val EDIABAS_SEC_0012 = ErrorCodes(362)
        val EDIABAS_SEC_0013 = ErrorCodes(363)
        val EDIABAS_SEC_0014 = ErrorCodes(364)
        val EDIABAS_SEC_0015 = ErrorCodes(365)
        val EDIABAS_SEC_0016 = ErrorCodes(366)
        val EDIABAS_SEC_0017 = ErrorCodes(367)
        val EDIABAS_SEC_0018 = ErrorCodes(368)
        val EDIABAS_SEC_0019 = ErrorCodes(369)
        val EDIABAS_SEC_0020 = ErrorCodes(370)
        val EDIABAS_SEC_0021 = ErrorCodes(371)
        val EDIABAS_SEC_0022 = ErrorCodes(372)
        val EDIABAS_SEC_0023 = ErrorCodes(373)
        val EDIABAS_SEC_0024 = ErrorCodes(374)
        val EDIABAS_SEC_0025 = ErrorCodes(375)
        val EDIABAS_SEC_0026 = ErrorCodes(376)
        val EDIABAS_SEC_0027 = ErrorCodes(377)
        val EDIABAS_SEC_0028 = ErrorCodes(378)
        val EDIABAS_SEC_0029 = ErrorCodes(379)
        val EDIABAS_SEC_0030 = ErrorCodes(380)
        val EDIABAS_SEC_0031 = ErrorCodes(381)
        val EDIABAS_SEC_0032 = ErrorCodes(382)
        val EDIABAS_SEC_0033 = ErrorCodes(383)
        val EDIABAS_SEC_0034 = ErrorCodes(384)
        val EDIABAS_SEC_0035 = ErrorCodes(385)
        val EDIABAS_SEC_0036 = ErrorCodes(386)
        val EDIABAS_SEC_0037 = ErrorCodes(387)
        val EDIABAS_SEC_0038 = ErrorCodes(388)
        val EDIABAS_SEC_0039 = ErrorCodes(389)
        val EDIABAS_SEC_0040 = ErrorCodes(390)
        val EDIABAS_SEC_0041 = ErrorCodes(391)
        val EDIABAS_SEC_0042 = ErrorCodes(392)
        val EDIABAS_SEC_0043 = ErrorCodes(393)
        val EDIABAS_SEC_0044 = ErrorCodes(394)
        val EDIABAS_SEC_0045 = ErrorCodes(395)
        val EDIABAS_SEC_0046 = ErrorCodes(396)
        val EDIABAS_SEC_0047 = ErrorCodes(397)
        val EDIABAS_SEC_0048 = ErrorCodes(398)
        val EDIABAS_SEC_0049 = ErrorCodes(399)
        val EDIABAS_SEC_LAST = ErrorCodes(399)
        val EDIABAS_ERROR_LAST = ErrorCodes(399)

        private val NAMES: Map<Int, String> = buildMap {
            put(0, "EDIABAS_ERR_NONE")
            put(10, "EDIABAS_IFH_0000")
            put(11, "EDIABAS_IFH_0001")
            put(12, "EDIABAS_IFH_0002")
            put(13, "EDIABAS_IFH_0003")
            put(14, "EDIABAS_IFH_0004")
            put(15, "EDIABAS_IFH_0005")
            put(16, "EDIABAS_IFH_0006")
            put(17, "EDIABAS_IFH_0007")
            put(18, "EDIABAS_IFH_0008")
            put(19, "EDIABAS_IFH_0009")
            put(20, "EDIABAS_IFH_0010")
            put(21, "EDIABAS_IFH_0011")
            put(22, "EDIABAS_IFH_0012")
            put(23, "EDIABAS_IFH_0013")
            put(24, "EDIABAS_IFH_0014")
            put(25, "EDIABAS_IFH_0015")
            put(26, "EDIABAS_IFH_0016")
            put(27, "EDIABAS_IFH_0017")
            put(28, "EDIABAS_IFH_0018")
            put(29, "EDIABAS_IFH_0019")
            put(30, "EDIABAS_IFH_0020")
            put(31, "EDIABAS_IFH_0021")
            put(32, "EDIABAS_IFH_0022")
            put(33, "EDIABAS_IFH_0023")
            put(34, "EDIABAS_IFH_0024")
            put(35, "EDIABAS_IFH_0025")
            put(36, "EDIABAS_IFH_0026")
            put(37, "EDIABAS_IFH_0027")
            put(38, "EDIABAS_IFH_0028")
            put(39, "EDIABAS_IFH_0029")
            put(40, "EDIABAS_IFH_0030")
            put(41, "EDIABAS_IFH_0031")
            put(42, "EDIABAS_IFH_0032")
            put(43, "EDIABAS_IFH_0033")
            put(44, "EDIABAS_IFH_0034")
            put(45, "EDIABAS_IFH_0035")
            put(46, "EDIABAS_IFH_0036")
            put(47, "EDIABAS_IFH_0037")
            put(48, "EDIABAS_IFH_0038")
            put(49, "EDIABAS_IFH_0039")
            put(50, "EDIABAS_IFH_0040")
            put(51, "EDIABAS_IFH_0041")
            put(52, "EDIABAS_IFH_0042")
            put(53, "EDIABAS_IFH_0043")
            put(54, "EDIABAS_IFH_0044")
            put(55, "EDIABAS_IFH_0045")
            put(56, "EDIABAS_IFH_0046")
            put(57, "EDIABAS_IFH_0047")
            put(58, "EDIABAS_IFH_0048")
            put(59, "EDIABAS_IFH_0049")
            put(60, "EDIABAS_BIP_0000")
            put(61, "EDIABAS_BIP_0001")
            put(62, "EDIABAS_BIP_0002")
            put(63, "EDIABAS_BIP_0003")
            put(64, "EDIABAS_BIP_0004")
            put(65, "EDIABAS_BIP_0005")
            put(66, "EDIABAS_BIP_0006")
            put(67, "EDIABAS_BIP_0007")
            put(68, "EDIABAS_BIP_0008")
            put(69, "EDIABAS_BIP_0009")
            put(70, "EDIABAS_BIP_0010")
            put(71, "EDIABAS_BIP_0011")
            put(72, "EDIABAS_BIP_0012")
            put(73, "EDIABAS_BIP_0013")
            put(74, "EDIABAS_BIP_0014")
            put(75, "EDIABAS_BIP_0015")
            put(76, "EDIABAS_BIP_0016")
            put(77, "EDIABAS_BIP_0017")
            put(78, "EDIABAS_BIP_0018")
            put(79, "EDIABAS_BIP_0019")
            put(80, "EDIABAS_BIP_0020")
            put(81, "EDIABAS_BIP_0021")
            put(82, "EDIABAS_BIP_0022")
            put(83, "EDIABAS_BIP_0023")
            put(84, "EDIABAS_BIP_0024")
            put(85, "EDIABAS_BIP_0025")
            put(86, "EDIABAS_BIP_0026")
            put(87, "EDIABAS_BIP_0027")
            put(88, "EDIABAS_BIP_0028")
            put(89, "EDIABAS_BIP_0029")
            put(90, "EDIABAS_SYS_0000")
            put(91, "EDIABAS_SYS_0001")
            put(92, "EDIABAS_SYS_0002")
            put(93, "EDIABAS_SYS_0003")
            put(94, "EDIABAS_SYS_0004")
            put(95, "EDIABAS_SYS_0005")
            put(96, "EDIABAS_SYS_0006")
            put(97, "EDIABAS_SYS_0007")
            put(98, "EDIABAS_SYS_0008")
            put(99, "EDIABAS_SYS_0009")
            put(100, "EDIABAS_SYS_0010")
            put(101, "EDIABAS_SYS_0011")
            put(102, "EDIABAS_SYS_0012")
            put(103, "EDIABAS_SYS_0013")
            put(104, "EDIABAS_SYS_0014")
            put(105, "EDIABAS_SYS_0015")
            put(106, "EDIABAS_SYS_0016")
            put(107, "EDIABAS_SYS_0017")
            put(108, "EDIABAS_SYS_0018")
            put(109, "EDIABAS_SYS_0019")
            put(110, "EDIABAS_SYS_0020")
            put(111, "EDIABAS_SYS_0021")
            put(112, "EDIABAS_SYS_0022")
            put(113, "EDIABAS_SYS_0023")
            put(114, "EDIABAS_SYS_0024")
            put(115, "EDIABAS_SYS_0025")
            put(116, "EDIABAS_SYS_0026")
            put(117, "EDIABAS_SYS_0027")
            put(118, "EDIABAS_SYS_0028")
            put(119, "EDIABAS_SYS_0029")
            put(120, "EDIABAS_API_0000")
            put(121, "EDIABAS_API_0001")
            put(122, "EDIABAS_API_0002")
            put(123, "EDIABAS_API_0003")
            put(124, "EDIABAS_API_0004")
            put(125, "EDIABAS_API_0005")
            put(126, "EDIABAS_API_0006")
            put(127, "EDIABAS_API_0007")
            put(128, "EDIABAS_API_0008")
            put(129, "EDIABAS_API_0009")
            put(130, "EDIABAS_API_0010")
            put(131, "EDIABAS_API_0011")
            put(132, "EDIABAS_API_0012")
            put(133, "EDIABAS_API_0013")
            put(134, "EDIABAS_API_0014")
            put(135, "EDIABAS_API_0015")
            put(136, "EDIABAS_API_0016")
            put(137, "EDIABAS_API_0017")
            put(138, "EDIABAS_API_0018")
            put(139, "EDIABAS_API_0019")
            put(140, "EDIABAS_API_0020")
            put(141, "EDIABAS_API_0021")
            put(142, "EDIABAS_API_0022")
            put(143, "EDIABAS_API_0023")
            put(144, "EDIABAS_API_0024")
            put(145, "EDIABAS_API_0025")
            put(146, "EDIABAS_API_0026")
            put(147, "EDIABAS_API_0027")
            put(148, "EDIABAS_API_0028")
            put(149, "EDIABAS_API_0029")
            put(150, "EDIABAS_NET_0000")
            put(151, "EDIABAS_NET_0001")
            put(152, "EDIABAS_NET_0002")
            put(153, "EDIABAS_NET_0003")
            put(154, "EDIABAS_NET_0004")
            put(155, "EDIABAS_NET_0005")
            put(156, "EDIABAS_NET_0006")
            put(157, "EDIABAS_NET_0007")
            put(158, "EDIABAS_NET_0008")
            put(159, "EDIABAS_NET_0009")
            put(160, "EDIABAS_NET_0010")
            put(161, "EDIABAS_NET_0011")
            put(162, "EDIABAS_NET_0012")
            put(163, "EDIABAS_NET_0013")
            put(164, "EDIABAS_NET_0014")
            put(165, "EDIABAS_NET_0015")
            put(166, "EDIABAS_NET_0016")
            put(167, "EDIABAS_NET_0017")
            put(168, "EDIABAS_NET_0018")
            put(169, "EDIABAS_NET_0019")
            put(170, "EDIABAS_NET_0020")
            put(171, "EDIABAS_NET_0021")
            put(172, "EDIABAS_NET_0022")
            put(173, "EDIABAS_NET_0023")
            put(174, "EDIABAS_NET_0024")
            put(175, "EDIABAS_NET_0025")
            put(176, "EDIABAS_NET_0026")
            put(177, "EDIABAS_NET_0027")
            put(178, "EDIABAS_NET_0028")
            put(179, "EDIABAS_NET_0029")
            put(180, "EDIABAS_NET_0030")
            put(181, "EDIABAS_NET_0031")
            put(182, "EDIABAS_NET_0032")
            put(183, "EDIABAS_NET_0033")
            put(184, "EDIABAS_NET_0034")
            put(185, "EDIABAS_NET_0035")
            put(186, "EDIABAS_NET_0036")
            put(187, "EDIABAS_NET_0037")
            put(188, "EDIABAS_NET_0038")
            put(189, "EDIABAS_NET_0039")
            put(190, "EDIABAS_NET_0040")
            put(191, "EDIABAS_NET_0041")
            put(192, "EDIABAS_NET_0042")
            put(193, "EDIABAS_NET_0043")
            put(194, "EDIABAS_NET_0044")
            put(195, "EDIABAS_NET_0045")
            put(196, "EDIABAS_NET_0046")
            put(197, "EDIABAS_NET_0047")
            put(198, "EDIABAS_NET_0048")
            put(199, "EDIABAS_NET_0049")
            put(200, "EDIABAS_IFH_0050")
            put(201, "EDIABAS_IFH_0051")
            put(202, "EDIABAS_IFH_0052")
            put(203, "EDIABAS_IFH_0053")
            put(204, "EDIABAS_IFH_0054")
            put(205, "EDIABAS_IFH_0055")
            put(206, "EDIABAS_IFH_0056")
            put(207, "EDIABAS_IFH_0057")
            put(208, "EDIABAS_IFH_0058")
            put(209, "EDIABAS_IFH_0059")
            put(210, "EDIABAS_IFH_0060")
            put(211, "EDIABAS_IFH_0061")
            put(212, "EDIABAS_IFH_0062")
            put(213, "EDIABAS_IFH_0063")
            put(214, "EDIABAS_IFH_0064")
            put(215, "EDIABAS_IFH_0065")
            put(216, "EDIABAS_IFH_0066")
            put(217, "EDIABAS_IFH_0067")
            put(218, "EDIABAS_IFH_0068")
            put(219, "EDIABAS_IFH_0069")
            put(220, "EDIABAS_IFH_0070")
            put(221, "EDIABAS_IFH_0071")
            put(222, "EDIABAS_IFH_0072")
            put(223, "EDIABAS_IFH_0073")
            put(224, "EDIABAS_IFH_0074")
            put(225, "EDIABAS_IFH_0075")
            put(226, "EDIABAS_IFH_0076")
            put(227, "EDIABAS_IFH_0077")
            put(228, "EDIABAS_IFH_0078")
            put(229, "EDIABAS_IFH_0079")
            put(230, "EDIABAS_IFH_0080")
            put(231, "EDIABAS_IFH_0081")
            put(232, "EDIABAS_IFH_0082")
            put(233, "EDIABAS_IFH_0083")
            put(234, "EDIABAS_IFH_0084")
            put(235, "EDIABAS_IFH_0085")
            put(236, "EDIABAS_IFH_0086")
            put(237, "EDIABAS_IFH_0087")
            put(238, "EDIABAS_IFH_0088")
            put(239, "EDIABAS_IFH_0089")
            put(240, "EDIABAS_IFH_0090")
            put(241, "EDIABAS_IFH_0091")
            put(242, "EDIABAS_IFH_0092")
            put(243, "EDIABAS_IFH_0093")
            put(244, "EDIABAS_IFH_0094")
            put(245, "EDIABAS_IFH_0095")
            put(246, "EDIABAS_IFH_0096")
            put(247, "EDIABAS_IFH_0097")
            put(248, "EDIABAS_IFH_0098")
            put(249, "EDIABAS_IFH_0099")
            put(250, "EDIABAS_RUN_0000")
            put(251, "EDIABAS_RUN_0001")
            put(252, "EDIABAS_RUN_0002")
            put(253, "EDIABAS_RUN_0003")
            put(254, "EDIABAS_RUN_0004")
            put(255, "EDIABAS_RUN_0005")
            put(256, "EDIABAS_RUN_0006")
            put(257, "EDIABAS_RUN_0007")
            put(258, "EDIABAS_RUN_0008")
            put(259, "EDIABAS_RUN_0009")
            put(260, "EDIABAS_RUN_0010")
            put(261, "EDIABAS_RUN_0011")
            put(262, "EDIABAS_RUN_0012")
            put(263, "EDIABAS_RUN_0013")
            put(264, "EDIABAS_RUN_0014")
            put(265, "EDIABAS_RUN_0015")
            put(266, "EDIABAS_RUN_0016")
            put(267, "EDIABAS_RUN_0017")
            put(268, "EDIABAS_RUN_0018")
            put(269, "EDIABAS_RUN_0019")
            put(270, "EDIABAS_RUN_0020")
            put(271, "EDIABAS_RUN_0021")
            put(272, "EDIABAS_RUN_0022")
            put(273, "EDIABAS_RUN_0023")
            put(274, "EDIABAS_RUN_0024")
            put(275, "EDIABAS_RUN_0025")
            put(276, "EDIABAS_RUN_0026")
            put(277, "EDIABAS_RUN_0027")
            put(278, "EDIABAS_RUN_0028")
            put(279, "EDIABAS_RUN_0029")
            put(280, "EDIABAS_RUN_0030")
            put(281, "EDIABAS_RUN_0031")
            put(282, "EDIABAS_RUN_0032")
            put(283, "EDIABAS_RUN_0033")
            put(284, "EDIABAS_RUN_0034")
            put(285, "EDIABAS_RUN_0035")
            put(286, "EDIABAS_RUN_0036")
            put(287, "EDIABAS_RUN_0037")
            put(288, "EDIABAS_RUN_0038")
            put(289, "EDIABAS_RUN_0039")
            put(290, "EDIABAS_RUN_0040")
            put(291, "EDIABAS_RUN_0041")
            put(292, "EDIABAS_RUN_0042")
            put(293, "EDIABAS_RUN_0043")
            put(294, "EDIABAS_RUN_0044")
            put(295, "EDIABAS_RUN_0045")
            put(296, "EDIABAS_RUN_0046")
            put(297, "EDIABAS_RUN_0047")
            put(298, "EDIABAS_RUN_0048")
            put(299, "EDIABAS_RUN_0049")
            put(300, "EDIABAS_RUN_0050")
            put(301, "EDIABAS_RUN_0051")
            put(302, "EDIABAS_RUN_0052")
            put(303, "EDIABAS_RUN_0053")
            put(304, "EDIABAS_RUN_0054")
            put(305, "EDIABAS_RUN_0055")
            put(306, "EDIABAS_RUN_0056")
            put(307, "EDIABAS_RUN_0057")
            put(308, "EDIABAS_RUN_0058")
            put(309, "EDIABAS_RUN_0059")
            put(310, "EDIABAS_RUN_0060")
            put(311, "EDIABAS_RUN_0061")
            put(312, "EDIABAS_RUN_0062")
            put(313, "EDIABAS_RUN_0063")
            put(314, "EDIABAS_RUN_0064")
            put(315, "EDIABAS_RUN_0065")
            put(316, "EDIABAS_RUN_0066")
            put(317, "EDIABAS_RUN_0067")
            put(318, "EDIABAS_RUN_0068")
            put(319, "EDIABAS_RUN_0069")
            put(320, "EDIABAS_RUN_0070")
            put(321, "EDIABAS_RUN_0071")
            put(322, "EDIABAS_RUN_0072")
            put(323, "EDIABAS_RUN_0073")
            put(324, "EDIABAS_RUN_0074")
            put(325, "EDIABAS_RUN_0075")
            put(326, "EDIABAS_RUN_0076")
            put(327, "EDIABAS_RUN_0077")
            put(328, "EDIABAS_RUN_0078")
            put(329, "EDIABAS_RUN_0079")
            put(330, "EDIABAS_RUN_0080")
            put(331, "EDIABAS_RUN_0081")
            put(332, "EDIABAS_RUN_0082")
            put(333, "EDIABAS_RUN_0083")
            put(334, "EDIABAS_RUN_0084")
            put(335, "EDIABAS_RUN_0085")
            put(336, "EDIABAS_RUN_0086")
            put(337, "EDIABAS_RUN_0087")
            put(338, "EDIABAS_RUN_0088")
            put(339, "EDIABAS_RUN_0089")
            put(340, "EDIABAS_RUN_0090")
            put(341, "EDIABAS_RUN_0091")
            put(342, "EDIABAS_RUN_0092")
            put(343, "EDIABAS_RUN_0093")
            put(344, "EDIABAS_RUN_0094")
            put(345, "EDIABAS_RUN_0095")
            put(346, "EDIABAS_RUN_0096")
            put(347, "EDIABAS_RUN_0097")
            put(348, "EDIABAS_RUN_0098")
            put(349, "EDIABAS_RUN_0099")
            put(350, "EDIABAS_SEC_0000")
            put(351, "EDIABAS_SEC_0001")
            put(352, "EDIABAS_SEC_0002")
            put(353, "EDIABAS_SEC_0003")
            put(354, "EDIABAS_SEC_0004")
            put(355, "EDIABAS_SEC_0005")
            put(356, "EDIABAS_SEC_0006")
            put(357, "EDIABAS_SEC_0007")
            put(358, "EDIABAS_SEC_0008")
            put(359, "EDIABAS_SEC_0009")
            put(360, "EDIABAS_SEC_0010")
            put(361, "EDIABAS_SEC_0011")
            put(362, "EDIABAS_SEC_0012")
            put(363, "EDIABAS_SEC_0013")
            put(364, "EDIABAS_SEC_0014")
            put(365, "EDIABAS_SEC_0015")
            put(366, "EDIABAS_SEC_0016")
            put(367, "EDIABAS_SEC_0017")
            put(368, "EDIABAS_SEC_0018")
            put(369, "EDIABAS_SEC_0019")
            put(370, "EDIABAS_SEC_0020")
            put(371, "EDIABAS_SEC_0021")
            put(372, "EDIABAS_SEC_0022")
            put(373, "EDIABAS_SEC_0023")
            put(374, "EDIABAS_SEC_0024")
            put(375, "EDIABAS_SEC_0025")
            put(376, "EDIABAS_SEC_0026")
            put(377, "EDIABAS_SEC_0027")
            put(378, "EDIABAS_SEC_0028")
            put(379, "EDIABAS_SEC_0029")
            put(380, "EDIABAS_SEC_0030")
            put(381, "EDIABAS_SEC_0031")
            put(382, "EDIABAS_SEC_0032")
            put(383, "EDIABAS_SEC_0033")
            put(384, "EDIABAS_SEC_0034")
            put(385, "EDIABAS_SEC_0035")
            put(386, "EDIABAS_SEC_0036")
            put(387, "EDIABAS_SEC_0037")
            put(388, "EDIABAS_SEC_0038")
            put(389, "EDIABAS_SEC_0039")
            put(390, "EDIABAS_SEC_0040")
            put(391, "EDIABAS_SEC_0041")
            put(392, "EDIABAS_SEC_0042")
            put(393, "EDIABAS_SEC_0043")
            put(394, "EDIABAS_SEC_0044")
            put(395, "EDIABAS_SEC_0045")
            put(396, "EDIABAS_SEC_0046")
            put(397, "EDIABAS_SEC_0047")
            put(398, "EDIABAS_SEC_0048")
            put(399, "EDIABAS_SEC_0049")
        }

        /** EdiabasNet.cs: ErrorDescription, indexed by code - EDIABAS_IFH_0000. */
        internal val ERROR_DESCRIPTION: Array<String> = arrayOf(
            "IFH-0000: INTERNAL ERROR",
            "IFH-0001: UART ERROR",
            "IFH-0002: NO RESPONSE FROM INTERFACE",
            "IFH-0003: DATATRANSMISSION TO INTERFACE DISTURBED",
            "IFH-0004: ERROR IN INTERFACE COMMAND",
            "IFH-0005: INTERNAL INTERFACE ERROR",
            "IFH-0006: COMMAND NOT ACCEPTED",
            "IFH-0007: WRONG UBATT",
            "IFH-0008: CONTROLUNIT CONNECTION ERROR",
            "IFH-0009: NO RESPONSE FROM CONTROLUNIT",
            "IFH-0010: DATATRANSMISSION TO CONTROLUNIT DISTURBED",
            "IFH-0011: UNKNOWN INTERFACE",
            "IFH-0012: BUFFER OVERFLOW",
            "IFH-0013: COMMAND NOT IMPLEMENTED",
            "IFH-0014: CONCEPT NOT IMPLEMENTED",
            "IFH-0015: UBATT ON/OFF ERROR",
            "IFH-0016: IGNITION ON/OFF ERROR",
            "IFH-0017: INTERFACE DEADLOCK ERROR",
            "IFH-0018: INITIALIZATION ERROR",
            "IFH-0019: DEVICE ACCESS ERROR",
            "IFH-0020: DRIVER ERROR",
            "IFH-0021: ILLEGAL PORT",
            "IFH-0022: DRIVER STATUS ERROR",
            "IFH-0023: INTERFACE STATUS ERROR",
            "IFH-0024: CANCEL FAILED",
            "IFH-0025: INTERFACE APPLICATION ERROR",
            "IFH-0026: SIMULATION ERROR",
            "IFH-0027: IFH NOT FOUND",
            "IFH-0028: ILLEGAL IFH VERSION",
            "IFH-0029: ACCESS DENIED",
            "IFH-0030: TASK COMMUNICATION ERROR",
            "IFH-0031: DATA OVERFLOW",
            "IFH-0032: IGNITION IS OFF",
            "IFH-0033",
            "IFH-0034: CONFIGURATION FILE NOT FOUND",
            "IFH-0035: CONFIGURATION ERROR",
            "IFH-0036: LOAD ERROR",
            "IFH-0037: LOW UBATT",
            "IFH-0038: INTERFACE COMMAND NOT IMPLEMENTED",
            "IFH-0039: EDIC USER INTERFACE NOT FOUND",
            "IFH-0040: ILLEGAL EDIC USER INTERFACE VERSION",
            "IFH-0041: ILLEGAL PARAMETERS",
            "IFH-0042: CARD INSTALLATION ERROR",
            "IFH-0043: COMMUNICATION TRACE ERROR",
            "IFH-0044: FLASH ERROR",
            "IFH-0045: RUNBOARD ERROR",
            "IFH-0046: EDIC API ACCESS ERROR",
            "IFH-0047: PLUGIN ERROR",
            "IFH-0048: PLUGIN FUNCTION ERROR",
            "IFH-0049: CSS DEVICE DETECTION ERROR",
            "BIP-0000: INTERNAL ERROR",
            "BIP-0001: OUT OF RANGE",
            "BIP-0002: IFH FUNCTION ERROR",
            "BIP-0003: OBJECT FILE ERROR",
            "BIP-0004: ILLEGAL OPCODE",
            "BIP-0005: STACK OVERFLOW",
            "BIP-0006: BEST FILE ERROR",
            "BIP-0007: DIVISION BY ZERO",
            "BIP-0008: BEST BREAK",
            "BIP-0009: BEST VERSION ERROR",
            "BIP-0010: CONSTANT DATA ACCESS ERROR",
            "BIP-0011: REAL ERROR",
            "BIP-0012: PLUG IN NOT FOUND",
            "BIP-0013: PLUG IN ERROR",
            "BIP-0014: PLUG IN VERSION ERROR",
            "BIP-0015: PLUG IN STACK ERROR",
            "BIP-0016: PLUG IN FUNCTION NOT FOUND",
            "BIP-0017: IFH CHANNEL ERROR",
            "BIP-0018: SYSTEM ERROR",
            "BIP-0019",
            "BIP-0020",
            "BIP-0021",
            "BIP-0022",
            "BIP-0023",
            "BIP-0024",
            "BIP-0025",
            "BIP-0026",
            "BIP-0027",
            "BIP-0028",
            "BIP-0029",
            "SYS-0000: INTERNAL ERROR",
            "SYS-0001: ILLEGAL FUNCTION",
            "SYS-0002: ECU OBJECT FILE NOT FOUND",
            "SYS-0003: ECU OBJECT FILE ERROR",
            "SYS-0004: ILLEGAL FORMAT OF ECU OBJECTFILE",
            "SYS-0005: OBJECT FILE NOT FOUND",
            "SYS-0006: GROUP OBJECT FILE ERROR",
            "SYS-0007: ILLEGAL FORMAT OF GROUP OBJECT FILE",
            "SYS-0008: JOB NOT FOUND",
            "SYS-0009: NO INITIALIZATION JOB",
            "SYS-0010: INITIALIZATION ERROR",
            "SYS-0011: NO IDENTIFICATIONJOB",
            "SYS-0012: IDENTIFICATION ERROR",
            "SYS-0013: UNEXPECTED RESULT",
            "SYS-0014: ILLEGAL FORMAT",
            "SYS-0015: TASK COMMUNICATION ERROR",
            "SYS-0016: ILLEGAL CONFIGURATION",
            "SYS-0017",
            "SYS-0018: END JOB ERROR",
            "SYS-0019: TIMER ERROR",
            "SYS-0020: BASE OBJECT FILE NOT FOUND",
            "SYS-0021: BASE OBJECT FILE ERROR",
            "SYS-0022: ILLEGAL FORMAT OF BASE OBJECT FILE",
            "SYS-0023: PASSWORD ERROR",
            "SYS-0024: ILLEGAL PASSWORD",
            "SYS-0025",
            "SYS-0026",
            "SYS-0027",
            "SYS-0028",
            "SYS-0029",
            "API-0000: INTERNAL ERROR",
            "API-0001: USER BREAK",
            "API-0002: MEMORY ALLOCATION ERROR",
            "API-0003: RESULT SETS OVERFLOW",
            "API-0004: RESULTS OVERFLOW",
            "API-0005: ILLEGAL RESULT FORMAT",
            "API-0006: ACCESS DENIED",
            "API-0007: INCORRECT CONFIGURATION FILE",
            "API-0008: TASK COMMUNICATION ERROR",
            "API-0009: EDIABAS NOT FOUND",
            "API-0010: ILLEGAL EDIABAS VERSION",
            "API-0011: ILLEGAL ECU PATH",
            "API-0012: SIGNAL SERVER NOT FOUND",
            "API-0013: INITIALIZATION ERROR",
            "API-0014: RESULT NOT FOUND",
            "API-0015: HOST COMMUNICATION ERROR",
            "API-0016: RESULT OVERFLOW",
            "API-0017: ARGUMENT OVERFLOW",
            "API-0018",
            "API-0019",
            "API-0020",
            "API-0021",
            "API-0022",
            "API-0023",
            "API-0024",
            "API-0025",
            "API-0026",
            "API-0027",
            "API-0028",
            "API-0029",
            "NET-0000: INTERNAL ERROR",
            "NET-0001: UNKNOWN ERROR",
            "NET-0002: ILLEGAL VERSION",
            "NET-0003: INITIALIZATION ERROR",
            "NET-0004: ILLEGAL CALL",
            "NET-0005: NO SUPPORT",
            "NET-0006: ACCESS DENIED",
            "NET-0007: SYSTEM ERROR",
            "NET-0008: NETWORK ERROR",
            "NET-0009: TIMEOUT",
            "NET-0010: BUFFER OVERFLOW",
            "NET-0011: ALREADY CONNECTED",
            "NET-0012: NO CONNECTION",
            "NET-0013: CONNECTION DISTURBED",
            "NET-0014: CONNECTION ABORTED",
            "NET-0015: HOST NOT FOUND",
            "NET-0016: HOST ERROR",
            "NET-0017: PROTOCOL NOT AVAILABLE",
            "NET-0018: UNKNOWN PROTOCOL",
            "NET-0019: UNKNOWN SERVICE",
            "NET-0020: UNKNOWN HOST",
            "NET-0021: SERVER NOT FOUND",
            "NET-0022: SECURITY ERROR",
            "NET-0023",
            "NET-0024",
            "NET-0025",
            "NET-0026",
            "NET-0027",
            "NET-0028",
            "NET-0029",
            "NET-0030",
            "NET-0031",
            "NET-0032",
            "NET-0033",
            "NET-0034",
            "NET-0035",
            "NET-0036",
            "NET-0037",
            "NET-0038",
            "NET-0039",
            "NET-0040",
            "NET-0041",
            "NET-0042",
            "NET-0043",
            "NET-0044",
            "NET-0045",
            "NET-0046",
            "NET-0047",
            "NET-0048",
            "NET-0049",
            "IFH-0050: ENTRY IN DYNAMIC CONFIGURATION NOT FOUND",
            "IFH-0051: INTERNAL DYNAMIC PROTOCOL ERROR",
            "IFH-0052: CONCEPT NOT AVALIABLE",
            "IFH-0053: ILLEGAL CONCEPT ID",
            "IFH-0054: ILLEGAL FUNCTION PARAMETER",
            "IFH-0055: CANNOT LOAD PROTOCOL TABLES",
            "IFH-0056: ILLEGAL CHANNEL",
            "IFH-0057: ERROR READ DIGITAL INPUTS",
            "IFH-0058: ERROR SET DIGITAL OUTPUTS",
            "IFH-0059: ERROR READ ANALOG INPUTS",
            "IFH-0060: RESUME AFTER SUSPEND STATE",
            "IFH-0061: INVALID ECU PARAMETERS FORMAT",
            "IFH-0062: BAD ECU PARAMETERS BUFFER",
            "IFH-0063: INVALID BUS CONFIGURATION",
            "IFH-0064: INVALID CONNECTION SETTINGS",
            "IFH-0065: FIRMWARE UPDATE ERROR",
            "IFH-0066: CHANNEL ERROR",
            "IFH-0067: ECU RESPONSE PENDING",
            "IFH-0068: TESTER ADDRESS ERROR",
            "IFH-0069: GATEWAY ERROR",
            "IFH-0070: SYSTEM ERROR",
            "IFH-0071: TELEGRAM FORMAT ERROR",
            "IFH-0072: ECU ACCESS COLLISION",
            "IFH-0073: PROXY ERROR",
            "IFH-0074: UDS COMMUNICATION ERROR",
            "IFH-0075",
            "IFH-0076",
            "IFH-0077",
            "IFH-0078",
            "IFH-0079",
            "IFH-0080",
            "IFH-0081",
            "IFH-0082",
            "IFH-0083",
            "IFH-0084",
            "IFH-0085",
            "IFH-0086",
            "IFH-0087",
            "IFH-0088",
            "IFH-0089",
            "IFH-0090",
            "IFH-0091",
            "IFH-0092",
            "IFH-0093",
            "IFH-0094",
            "IFH-0095",
            "IFH-0096",
            "IFH-0097",
            "IFH-0098",
            "IFH-0099",
            "RUN-0000",
            "RUN-0001",
            "RUN-0002",
            "RUN-0003",
            "RUN-0004",
            "RUN-0005",
            "RUN-0006",
            "RUN-0007",
            "RUN-0008",
            "RUN-0009",
            "RUN-0010",
            "RUN-0011",
            "RUN-0012",
            "RUN-0013",
            "RUN-0014",
            "RUN-0015",
            "RUN-0016",
            "RUN-0017",
            "RUN-0018",
            "RUN-0019",
            "RUN-0020",
            "RUN-0021",
            "RUN-0022",
            "RUN-0023",
            "RUN-0024",
            "RUN-0025",
            "RUN-0026",
            "RUN-0027",
            "RUN-0028",
            "RUN-0029",
            "RUN-0030",
            "RUN-0031",
            "RUN-0032",
            "RUN-0033",
            "RUN-0034",
            "RUN-0035",
            "RUN-0036",
            "RUN-0037",
            "RUN-0038",
            "RUN-0039",
            "RUN-0040",
            "RUN-0041",
            "RUN-0042",
            "RUN-0043",
            "RUN-0044",
            "RUN-0045",
            "RUN-0046",
            "RUN-0047",
            "RUN-0048",
            "RUN-0049",
            "RUN-0050",
            "RUN-0051",
            "RUN-0052",
            "RUN-0053",
            "RUN-0054",
            "RUN-0055",
            "RUN-0056",
            "RUN-0057",
            "RUN-0058",
            "RUN-0059",
            "RUN-0060",
            "RUN-0061",
            "RUN-0062",
            "RUN-0063",
            "RUN-0064",
            "RUN-0065",
            "RUN-0066",
            "RUN-0067",
            "RUN-0068",
            "RUN-0069",
            "RUN-0070",
            "RUN-0071",
            "RUN-0072",
            "RUN-0073",
            "RUN-0074",
            "RUN-0075",
            "RUN-0076",
            "RUN-0077",
            "RUN-0078",
            "RUN-0079",
            "RUN-0080",
            "RUN-0081",
            "RUN-0082",
            "RUN-0083",
            "RUN-0084",
            "RUN-0085",
            "RUN-0086",
            "RUN-0087",
            "RUN-0088",
            "RUN-0089",
            "RUN-0090",
            "RUN-0091",
            "RUN-0092",
            "RUN-0093",
            "RUN-0094",
            "RUN-0095",
            "RUN-0096",
            "RUN-0097",
            "RUN-0098",
            "RUN-0099",
            "SEC-0000: INTERNAL ERROR",
            "SEC-0001: UNKNOWN CERTIFICATE",
            "SEC-0002: CERTIFICATE VERIFICATION ERROR",
            "SEC-0003: OWNERSHIP VERIFICATION ERROR",
            "SEC-0004: VERSION ERROR",
            "SEC-0005: SYSTEM ERROR",
            "SEC-0006: ACCESS ERROR",
            "SEC-0007: UNKNOWN ERROR",
            "SEC-0008: FILE OR PATH NOT FOUND",
            "SEC-0009: INITIALIZATION ERROR",
            "SEC-0010: SERVICE NOT SUPPORTED",
            "SEC-0011: SUB FUNCTION NOT SUPPORTED",
            "SEC-0012: MESSAGE LENGTH OR FORMAT ERROR",
            "SEC-0013: CONDITIONS ERROR",
            "SEC-0014: REQUEST SEQUENCE ERROR",
            "SEC-0015: CERTIFICATE TIME PERIOD ERROR",
            "SEC-0016: CERTIFICATE SIGNATURE ERROR",
            "SEC-0017: CERTIFICATE CHAIN OF TRUST ERROR",
            "SEC-0018: CERTIFICATE TYPE ERROR",
            "SEC-0019: CERTIFICATE FORMAT ERROR",
            "SEC-0020: CERTIFICATE CONTENT ERROR",
            "SEC-0021: CERTIFICATE SCOPE ERROR",
            "SEC-0022: CERTIFICATE REVOKE ERROR",
            "SEC-0023: OWNERSHIP VERIFICATION ERROR",
            "SEC-0024: CHALLENGE CALCULATION ERROR",
            "SEC-0025: SETTING ACCESS RIGHTS ERROR",
            "SEC-0026: SESSION KEY ERROR",
            "SEC-0027: CONFIGURATION DATA ERROR",
            "SEC-0028: DEAUTHENTICATION ERROR",
            "SEC-0029",
            "SEC-0030: UNKNOWN PROTOCOL",
            "SEC-0031: PROTOCOL NOT SUPPORTED",
            "SEC-0032: PROTOCOL ERROR",
            "SEC-0033: CIPHER NOT SUPPORTED",
            "SEC-0034: CERTIFICATE ERROR",
            "SEC-0035: CERTIFICATE EXPIRED",
            "SEC-0036: CERTIFICATE LOOKUP ERROR",
            "SEC-0037: KEY ERROR",
            "SEC-0038: KEY NOT FOUND",
            "SEC-0039: SESSION ERROR",
            "SEC-0040: PENDING ERROR",
            "SEC-0041: COMMUNICATION ERROR",
            "SEC-0042: DIGEST ERROR",
            "SEC-0043: SIGN ERROR",
            "SEC-0044: SIGN VERIFICATION ERROR",
            "SEC-0045: BIG NUM ERROR",
            "SEC-0046: CONTAINER ERROR",
            "SEC-0047: BACKEND ERROR",
            "SEC-0048: TIMEOUT ERROR",
            "SEC-0049: AUTHENTICATION LOST",
        )
    }
}
